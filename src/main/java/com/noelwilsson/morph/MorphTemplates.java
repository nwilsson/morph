package com.noelwilsson.morph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * One never-spawned instance per mob type, used to ask the mob's own code about itself (does water hurt it, is it
 * undead). Asking the real class keeps the morph faithful without copying vanilla's rules into a table.
 *
 * <p>Templates belong to the level they were made in, since some of what they're asked reads it (a horse's step
 * sound looks at the block underfoot). So each dimension has its own, and they're made again for a new world: a
 * template left over from a world that was closed waits forever for its chunks.
 */
public final class MorphTemplates {
	private record Cache(Level level, Map<EntityType<?>, LivingEntity> types, Map<MorphVariant, LivingEntity> looks) {}

	private static final Map<ResourceKey<Level>, Cache> SERVER = new ConcurrentHashMap<>();
	private static final Map<ResourceKey<Level>, Cache> CLIENT = new ConcurrentHashMap<>();

	private MorphTemplates() {}

	/** When the server stops: its worlds are gone, so are their templates. */
	public static void forgetServer() {
		SERVER.clear();
	}

	private static Cache cache(Level level) {
		Map<ResourceKey<Level>, Cache> caches = level.isClientSide() ? CLIENT : SERVER;
		Cache cache = caches.get(level.dimension());
		if (cache == null || cache.level() != level) {
			cache = new Cache(level, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
			caches.put(level.dimension(), cache);
		}
		return cache;
	}

	public static @Nullable LivingEntity get(EntityType<?> type, Level level) {
		Map<EntityType<?>, LivingEntity> cache = cache(level).types();
		LivingEntity cached = cache.get(type);
		if (cached != null) {
			return cached;
		}
		Entity created = type.create(level, EntitySpawnReason.LOAD);
		if (created instanceof LivingEntity living) {
			cache.put(type, living);
			return living;
		}
		return null;
	}

	/** The template of the mob this player is, or null if they aren't morphed. */
	public static @Nullable LivingEntity body(Player player) {
		EntityType<?> type = MorphState.current(player);
		return type == null ? null : get(type, player.level());
	}

	/** A template of this exact look (a baby zombie's voice is higher), or of the plain mob if the look can't be applied. */
	public static @Nullable LivingEntity get(MorphVariant variant, Level level) {
		EntityType<?> type = variant.type();
		if (type == null) {
			return null;
		}
		if (variant.isDefault()) {
			return get(type, level);
		}
		Map<MorphVariant, LivingEntity> cache = cache(level).looks();
		LivingEntity cached = cache.get(variant);
		if (cached != null) {
			return cached;
		}
		LivingEntity created;
		try {
			created = MorphVariant.create(type, variant.data(), level);
		} catch (RuntimeException e) {
			Morph.LOGGER.debug("Couldn't make a template of {}", variant.snbt(), e);
			created = null;
		}
		if (created == null) {
			return get(type, level);
		}
		cache.put(variant, created);
		return created;
	}
}

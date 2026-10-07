package com.noelwilsson.morph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * One never-spawned instance per mob type, used to ask the mob's own code about itself (does water hurt it, is it
 * undead). Asking the real class keeps the morph faithful without copying vanilla's rules into a table.
 */
public final class MorphTemplates {
	private static final Map<EntityType<?>, LivingEntity> SERVER = new ConcurrentHashMap<>();
	private static final Map<EntityType<?>, LivingEntity> CLIENT = new ConcurrentHashMap<>();
	/** Templates with a look applied, for what depends on it: a baby zombie's voice is higher. */
	private static final Map<MorphVariant, LivingEntity> SERVER_LOOKS = new ConcurrentHashMap<>();
	private static final Map<MorphVariant, LivingEntity> CLIENT_LOOKS = new ConcurrentHashMap<>();

	private MorphTemplates() {}

	public static @Nullable LivingEntity get(EntityType<?> type, Level level) {
		Map<EntityType<?>, LivingEntity> cache = level.isClientSide() ? CLIENT : SERVER;
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

	/** A template of this exact look, or of the plain mob if the look can't be applied. */
	public static @Nullable LivingEntity get(MorphVariant variant, Level level) {
		EntityType<?> type = variant.type();
		if (type == null) {
			return null;
		}
		if (variant.isDefault()) {
			return get(type, level);
		}
		Map<MorphVariant, LivingEntity> cache = level.isClientSide() ? CLIENT_LOOKS : SERVER_LOOKS;
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

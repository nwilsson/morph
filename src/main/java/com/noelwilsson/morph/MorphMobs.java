package com.noelwilsson.morph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * What each mob's body comes with, from data files: {@code data/<namespace>/morph/mob/<path>.json} describes the mob
 * {@code <namespace>:<path>}, so {@code data/minecraft/morph/mob/blaze.json} is the blaze. Morphed ships one for every
 * vanilla mob that has something; a datapack replaces a mob's file to change it, or adds one for a modded mob. Mobs
 * without a file still get their body, health and fire immunity, just no abilities or power.
 */
public final class MorphMobs {
	/** What a melee hit by this mob does on top of its damage. */
	public record OnHit(List<MobEffectInstance> effects, float fireSeconds, double launch) {
		public static final Codec<OnHit> CODEC = RecordCodecBuilder.create(i -> i.group(
			MobEffectInstance.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(OnHit::effects),
			ExtraCodecs.NON_NEGATIVE_FLOAT.optionalFieldOf("fire_seconds", 0.0F).forGetter(OnHit::fireSeconds),
			Codec.DOUBLE.optionalFieldOf("launch", 0.0).forGetter(OnHit::launch)
		).apply(i, OnHit::new));
	}

	public record Mob(Set<MorphAbilities.Ability> abilities, Optional<MorphPowers.Power> power, Optional<OnHit> onHit) {
		private static final Codec<Set<MorphAbilities.Ability>> ABILITIES = MorphAbilities.Ability.CODEC.listOf()
			.xmap(list -> list.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(list)), List::copyOf);
		public static final Codec<Mob> CODEC = RecordCodecBuilder.create(i -> i.group(
			ABILITIES.optionalFieldOf("abilities", Set.of()).forGetter(Mob::abilities),
			MorphPowers.Power.CODEC.optionalFieldOf("power").forGetter(Mob::power),
			OnHit.CODEC.optionalFieldOf("on_hit").forGetter(Mob::onHit)
		).apply(i, Mob::new));
	}

	/** The part clients need: abilities (movement runs on both sides) and the power's name for the HUD. */
	public record Synced(Set<MorphAbilities.Ability> abilities, Optional<String> power) {
		private static final StreamCodec<ByteBuf, Set<MorphAbilities.Ability>> ABILITY_BITS = ByteBufCodecs.VAR_INT.map(bits -> {
			EnumSet<MorphAbilities.Ability> set = EnumSet.noneOf(MorphAbilities.Ability.class);
			for (MorphAbilities.Ability ability : MorphAbilities.Ability.values()) {
				if ((bits & (1 << ability.ordinal())) != 0) {
					set.add(ability);
				}
			}
			return set;
		}, set -> {
			int bits = 0;
			for (MorphAbilities.Ability ability : set) {
				bits |= 1 << ability.ordinal();
			}
			return bits;
		});
		public static final StreamCodec<ByteBuf, Synced> STREAM_CODEC = StreamCodec.composite(
			ABILITY_BITS, Synced::abilities,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs::optional), Synced::power,
			Synced::new);
	}

	/** Every mob's {@link Synced} part, by mob id. A global attachment, so it reaches every client. */
	public static final AttachmentType<Map<Identifier, Synced>> SYNCED = AttachmentRegistry.<Map<Identifier, Synced>>builder()
		.syncWith(ByteBufCodecs.<ByteBuf, Identifier, Synced, Map<Identifier, Synced>>map(HashMap::new, Identifier.STREAM_CODEC, Synced.STREAM_CODEC),
			AttachmentSyncPredicate.all())
		.buildAndRegister(Morph.id("mobs"));

	/** Server side: the last loaded files, by mob id. */
	private static Map<Identifier, Mob> loaded = Map.of();

	private MorphMobs() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Morph.id("mobs"), new Loader());
		ServerLifecycleEvents.SERVER_STARTED.register(MorphMobs::sync);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> sync(server));
	}

	/** Server side: this mob's file, or null if it has none. */
	public static @Nullable Mob get(EntityType<?> type) {
		return loaded.get(BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	/** Either side: what the client knows about this mob, or null if it has no file. */
	public static @Nullable Synced synced(EntityType<?> type, Level level) {
		return level.globalAttachments().getAttachedOrElse(SYNCED, Map.of()).get(BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	private static void sync(MinecraftServer server) {
		Map<Identifier, Synced> synced = new HashMap<>();
		loaded.forEach((id, mob) -> synced.put(id, new Synced(mob.abilities(), mob.power().map(MorphPowers.Power::name))));
		server.globalAttachments().setAttached(SYNCED, Map.copyOf(synced));
	}

	private static final class Loader extends SimpleJsonResourceReloadListener<Mob> {
		Loader() {
			super(Mob.CODEC, FileToIdConverter.json("morph/mob"));
		}

		@Override
		protected void apply(Map<Identifier, Mob> mobs, ResourceManager manager, ProfilerFiller profiler) {
			Map<Identifier, Mob> known = new HashMap<>();
			mobs.forEach((id, mob) -> {
				// A file for a mod that isn't installed is fine: modpacks ship files for mods they may drop.
				if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
					known.put(id, mob);
				} else {
					Morph.LOGGER.debug("Morph data for {}, which isn't a mob in this game", id);
				}
			});
			loaded = Map.copyOf(known);
			Morph.LOGGER.info("Loaded morph data for {} mobs", loaded.size());
		}
	}
}

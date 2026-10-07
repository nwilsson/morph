package com.noelwilsson.morph;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Per-player morph data (synced attachments) and the server-side rules that apply a morph. */
public final class MorphState {
	/** Mobs (and each look of them) this player has killed, so can morph into. Survives death. */
	public static final AttachmentType<List<MorphVariant>> UNLOCKED = AttachmentRegistry.<List<MorphVariant>>builder()
		.persistent(MorphVariant.CODEC.listOf())
		.copyOnDeath()
		.initializer(List::of)
		.syncWith(MorphVariant.STREAM_CODEC.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Morph.id("unlocked"));

	/** The mob the player currently is. Everyone needs it to draw the player. Lost on death. */
	public static final AttachmentType<MorphVariant> CURRENT = AttachmentRegistry.<MorphVariant>builder()
		.persistent(MorphVariant.CODEC)
		.syncWith(MorphVariant.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Morph.id("current"));

	/** Looks the player starred in the sidebar, for the radial menu. Survives death. */
	public static final AttachmentType<List<MorphVariant>> FAVORITES = AttachmentRegistry.<List<MorphVariant>>builder()
		.persistent(MorphVariant.CODEC.listOf())
		.copyOnDeath()
		.initializer(List::of)
		.syncWith(MorphVariant.STREAM_CODEC.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Morph.id("favorites"));

	/** The last mob the player was, so the toggle key can go back to it. Survives death. */
	public static final AttachmentType<MorphVariant> LAST = AttachmentRegistry.<MorphVariant>builder()
		.persistent(MorphVariant.CODEC)
		.copyOnDeath()
		.buildAndRegister(Morph.id("last"));

	/** Kills so far of each mob not unlocked yet, by mob id, while morph:kills_to_unlock is above 1. Survives death. */
	public static final AttachmentType<Map<String, Integer>> KILLS = AttachmentRegistry.<Map<String, Integer>>builder()
		.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT))
		.copyOnDeath()
		.buildAndRegister(Morph.id("kills"));

	private static final Identifier HEALTH_MODIFIER = Morph.id("health");
	private static final Identifier SPEED_MODIFIER = Morph.id("speed");
	private static final Identifier JUMP_MODIFIER = Morph.id("jump");
	private static final Identifier ATTACK_MODIFIER = Morph.id("attack");
	private static final double PLAYER_BASE_ATTACK = 1.0;
	private static final double PLAYER_BASE_HEALTH = 20.0;
	/** Health is carried between bodies in steps of this fraction of a point. */
	private static final int HEALTH_GRID = 1024;
	/** Ability effects are refreshed below this many ticks left, so they never run out while morphed. */
	private static final int EFFECT_REFRESH = 40;
	private static final int EFFECT_DURATION = 100;

	private MorphState() {}

	public static void init() {
		// Class loading registers the attachments.
	}

	public static @Nullable EntityType<?> current(Player player) {
		MorphVariant variant = player.getAttached(CURRENT);
		return variant == null ? null : variant.type();
	}

	public static @Nullable MorphVariant currentVariant(Player player) {
		return player.getAttached(CURRENT);
	}

	/** Whether this mob can be a morph at all: a living mob, not a player, and not in #morph:blocked. */
	public static boolean canMorphInto(EntityType<?> type) {
		return type != EntityTypes.PLAYER && type != EntityTypes.MANNEQUIN && type != EntityTypes.ARMOR_STAND && DefaultAttributes.hasSupplier(type)
			&& !MorphRules.isBlocked(type);
	}

	/**
	 * Counts a kill toward unlocking this mob and returns the total so far. Only mobs with no look unlocked yet are
	 * counted: once a mob is known, its other looks unlock on the first kill.
	 */
	public static int addKill(ServerPlayer player, EntityType<?> type) {
		Map<String, Integer> kills = new HashMap<>(player.getAttachedOrElse(KILLS, Map.of()));
		int count = kills.merge(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), 1, Integer::sum);
		player.setAttached(KILLS, Map.copyOf(kills));
		return count;
	}

	private static void clearKills(ServerPlayer player, EntityType<?> type) {
		Map<String, Integer> kills = player.getAttached(KILLS);
		String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		if (kills != null && kills.containsKey(key)) {
			Map<String, Integer> updated = new HashMap<>(kills);
			updated.remove(key);
			player.setAttached(KILLS, Map.copyOf(updated));
		}
	}

	/** morph:keep_morphs_on_death off: a dead player starts over, with no morphs, favourites or kill counts. */
	public static void forgetAll(ServerPlayer player) {
		player.removeAttached(UNLOCKED);
		player.removeAttached(FAVORITES);
		player.removeAttached(LAST);
		player.removeAttached(KILLS);
	}

	public static boolean isFavorite(Player player, MorphVariant variant) {
		return player.getAttachedOrElse(FAVORITES, List.of()).contains(variant);
	}

	/** Stars or unstars an unlocked look. Returns true if it's now a favourite. */
	public static boolean toggleFavorite(ServerPlayer player, MorphVariant variant) {
		List<MorphVariant> favorites = new ArrayList<>(player.getAttachedOrElse(FAVORITES, List.of()));
		boolean added = !favorites.remove(variant);
		if (added) {
			if (!isUnlocked(player, variant)) {
				return false;
			}
			favorites.add(variant);
		}
		player.setAttached(FAVORITES, List.copyOf(favorites));
		return added;
	}

	/**
	 * The toggle key: morphed, go back to yourself; yourself, become the last mob you were. Returns false if there is
	 * nothing to go back to (never morphed, or that morph is locked or blocked now).
	 */
	public static boolean toggle(ServerPlayer player) {
		if (player.getAttached(CURRENT) != null) {
			unmorph(player);
			return true;
		}
		MorphVariant last = player.getAttached(LAST);
		EntityType<?> type = last == null ? null : last.type();
		if (type == null || !canMorphInto(type) || !isUnlocked(player, last)) {
			return false;
		}
		morph(player, last);
		return true;
	}

	/** Unlocks the mob's default look. Returns true if this is a new unlock. */
	public static boolean unlock(ServerPlayer player, EntityType<?> type) {
		return unlock(player, MorphVariant.of(type));
	}

	/** Returns true if this is a new unlock. */
	public static boolean unlock(ServerPlayer player, MorphVariant variant) {
		List<MorphVariant> unlocked = player.getAttachedOrCreate(UNLOCKED);
		if (unlocked.contains(variant)) {
			return false;
		}
		List<MorphVariant> updated = new ArrayList<>(unlocked);
		updated.add(variant);
		player.setAttached(UNLOCKED, List.copyOf(updated));
		EntityType<?> type = variant.type();
		if (type != null) {
			clearKills(player, type);
		}
		return true;
	}

	/** Any look of this mob. */
	public static boolean isUnlocked(Player player, EntityType<?> type) {
		return !unlocked(player, type).isEmpty();
	}

	public static boolean isUnlocked(Player player, MorphVariant variant) {
		return player.getAttachedOrElse(UNLOCKED, List.of()).contains(variant);
	}

	/** The looks of this mob the player has unlocked, oldest first. */
	public static List<MorphVariant> unlocked(Player player, EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return player.getAttachedOrElse(UNLOCKED, List.<MorphVariant>of()).stream().filter(variant -> variant.id().equals(id)).toList();
	}

	/** Morphs into the first unlocked look of this mob, or its default look. */
	public static void morph(ServerPlayer player, EntityType<?> type) {
		morph(player, unlocked(player, type).stream().findFirst().orElseGet(() -> MorphVariant.of(type)));
	}

	public static void morph(ServerPlayer player, MorphVariant variant) {
		double fraction = healthFraction(player);
		float before = player.getHealth();
		clearAbilities(player);
		player.setAttached(CURRENT, variant);
		player.setAttached(LAST, variant);
		MorphPowers.changedBody(player);
		player.refreshDimensions();
		apply(player);
		setHealthFraction(player, fraction, before);
		EntityType<?> type = variant.type();
		if (type != null && MorphAbilities.of(type).contains(MorphAbilities.Ability.FLY) && !MorphRules.mayFly(player)
			&& MorphRules.allowFlight(player.level())) {
			player.sendOverlayMessage(Component.literal("You can't fly yet. By default, flying unlocks once you've been to the End."));
		}
	}

	public static void unmorph(ServerPlayer player) {
		if (player.getAttached(CURRENT) == null) {
			return;
		}
		double fraction = healthFraction(player);
		float before = player.getHealth();
		player.removeAttached(CURRENT);
		clearAbilities(player);
		MorphPowers.changedBody(player);
		player.refreshDimensions();
		setHealthFraction(player, fraction, before);
	}

	/**
	 * Health carries over as a fraction of max: half-dead stays half-dead in any body. Taken before the old body's
	 * max health goes, since dropping max health also cuts health down to it.
	 */
	private static double healthFraction(ServerPlayer player) {
		return Mth.clamp((double) player.getHealth() / player.getMaxHealth(), 0.0, 1.0);
	}

	/**
	 * Rounded down to a fine grid, never to whole points: 1 of 20 health is a fifth of a point as a chicken and 1 again
	 * after, where rounding the chicken up to a whole point would turn back into 5. Rounding down means switching back
	 * and forth can only lose a sliver, never gain. Never zero: morphing doesn't kill.
	 */
	private static void setHealthFraction(ServerPlayer player, double fraction, float before) {
		if (player.isDeadOrDying()) {
			return;
		}
		float health = (float) (Math.floor(fraction * player.getMaxHealth() * HEALTH_GRID) / HEALTH_GRID);
		player.setHealth(health > 0.0F ? health : Math.min(before, 1.0F / HEALTH_GRID));
	}

	/** Called every second for every player: (re)applies the morph so it survives relogs and gamemode changes. */
	public static void apply(ServerPlayer player) {
		EntityType<?> type = current(player);
		if (type == null) {
			return;
		}
		// A datapack can block a mob while someone is it (/reload): put them back in their own body.
		if (!canMorphInto(type)) {
			unmorph(player);
			player.sendSystemMessage(Component.literal("Morphing into ").append(type.getDescription()).append(" is turned off here."));
			return;
		}
		@SuppressWarnings("unchecked")
		AttributeSupplier mob = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
		setModifier(player, Attributes.MAX_HEALTH, HEALTH_MODIFIER, mob.getValue(Attributes.MAX_HEALTH) - PLAYER_BASE_HEALTH,
			AttributeModifier.Operation.ADD_VALUE);
		// Hit as hard as the mob does. Mobs that can't attack keep the player's fists.
		if (mob.hasAttribute(Attributes.ATTACK_DAMAGE) && mob.getValue(Attributes.ATTACK_DAMAGE) > PLAYER_BASE_ATTACK) {
			setModifier(player, Attributes.ATTACK_DAMAGE, ATTACK_MODIFIER, mob.getValue(Attributes.ATTACK_DAMAGE) - PLAYER_BASE_ATTACK,
				AttributeModifier.Operation.ADD_VALUE);
		}
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}

		Set<MorphAbilities.Ability> abilities = MorphAbilities.of(type);
		if (abilities.contains(MorphAbilities.Ability.SPEED)) {
			setModifier(player, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER, 0.3, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		}
		if (abilities.contains(MorphAbilities.Ability.JUMP)) {
			setModifier(player, Attributes.JUMP_STRENGTH, JUMP_MODIFIER, 0.2, AttributeModifier.Operation.ADD_VALUE);
		}
		// Fliers that may not fly (morph:allow_flight off, or morph:flight not earned yet) flutter down instead. The
		// rules can change while someone is in the air, and the advancement can be earned mid-morph.
		if (abilities.contains(MorphAbilities.Ability.FLY) && !MorphRules.mayFly(player)) {
			abilities.remove(MorphAbilities.Ability.FLY);
			abilities.add(MorphAbilities.Ability.SLOW_FALL);
			if (player.getAbilities().mayfly && !player.isCreative() && !player.isSpectator()) {
				player.getAbilities().mayfly = false;
				player.getAbilities().flying = false;
				player.onUpdateAbilities();
			}
		}
		if (abilities.contains(MorphAbilities.Ability.FLY) && !player.getAbilities().mayfly) {
			player.getAbilities().mayfly = true;
			player.onUpdateAbilities();
		}
		effect(player, abilities, MorphAbilities.Ability.SLOW_FALL, MobEffects.SLOW_FALLING);
		effect(player, abilities, MorphAbilities.Ability.WATER_BREATHING, MobEffects.WATER_BREATHING);
		effect(player, abilities, MorphAbilities.Ability.SWIM, MobEffects.DOLPHINS_GRACE);
		effect(player, abilities, MorphAbilities.Ability.FIRE_IMMUNE, MobEffects.FIRE_RESISTANCE);
		// Night vision flickers when it gets low, so give it more headroom.
		if (abilities.contains(MorphAbilities.Ability.NIGHT_VISION)) {
			MobEffectInstance active = player.getEffect(MobEffects.NIGHT_VISION);
			if (active == null || active.getDuration() < 300) {
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, false));
			}
		}
	}

	/**
	 * Every tick: the mob's weaknesses. Water and rain hurting blazes/endermen and undead potions are vanilla's own checks,
	 * answered by the template (see LivingEntityMixin); sunburn and fish suffocating are reproduced here.
	 */
	public static void tickWeaknesses(ServerPlayer player) {
		EntityType<?> type = current(player);
		if (type == null || !player.isAlive() || player.isCreative() || player.isSpectator()) {
			return;
		}
		if (type.builtInRegistryHolder().is(EntityTypeTags.BURN_IN_DAYLIGHT) && isSunBurnTick(player)) {
			ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
			if (helmet.isEmpty()) {
				player.igniteForSeconds(8.0F);
			} else if (helmet.isDamageableItem()) {
				helmet.hurtAndBreak(player.getRandom().nextInt(2), player, EquipmentSlot.HEAD);
			}
		}
		if (MorphAbilities.of(type).contains(MorphAbilities.Ability.DRIES_OUT) && !player.isInWater()) {
			// Vanilla refills 4 air a tick out of water; take 5 so a fish loses 1 a tick, like a real one.
			int air = player.getAirSupply() - 5;
			if (air <= -20) {
				air = 0;
				player.hurtServer(player.level(), player.damageSources().dryOut(), 2.0F);
			}
			player.setAirSupply(air);
		}
	}

	/** Mob.isSunBurnTick, which is private: bright, open sky, not wet, and a random roll like the real mob. */
	private static boolean isSunBurnTick(ServerPlayer player) {
		if (!player.level().environmentAttributes().getValue(EnvironmentAttributes.MONSTERS_BURN, player.position())) {
			return false;
		}
		float brightness = player.getLightLevelDependentMagicValue();
		BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		boolean wet = player.isInWaterOrRain() || player.isInPowderSnow || player.wasInPowderSnow;
		return brightness > 0.5F && player.getRandom().nextFloat() * 30.0F < (brightness - 0.4F) * 2.0F && !wet && player.level().canSeeSky(eye);
	}

	private static void effect(ServerPlayer player, Set<MorphAbilities.Ability> abilities, MorphAbilities.Ability ability, Holder<MobEffect> effect) {
		if (!abilities.contains(ability)) {
			return;
		}
		MobEffectInstance active = player.getEffect(effect);
		if (active == null || active.getDuration() < EFFECT_REFRESH) {
			player.addEffect(new MobEffectInstance(effect, EFFECT_DURATION, 0, true, false, false));
		}
	}

	private static void clearAbilities(ServerPlayer player) {
		removeModifier(player, Attributes.MAX_HEALTH, HEALTH_MODIFIER);
		removeModifier(player, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER);
		removeModifier(player, Attributes.JUMP_STRENGTH, JUMP_MODIFIER);
		removeModifier(player, Attributes.ATTACK_DAMAGE, ATTACK_MODIFIER);
		if (!player.isCreative() && !player.isSpectator() && player.getAbilities().mayfly) {
			player.getAbilities().mayfly = false;
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
		}
		// Only our own effects: ambient and hidden. Real potions stay.
		for (Holder<MobEffect> effect : List.of(MobEffects.SLOW_FALLING, MobEffects.WATER_BREATHING, MobEffects.DOLPHINS_GRACE,
			MobEffects.FIRE_RESISTANCE, MobEffects.NIGHT_VISION)) {
			Optional.ofNullable(player.getEffect(effect))
				.filter(instance -> instance.isAmbient() && !instance.isVisible())
				.ifPresent(instance -> player.removeEffect(effect));
		}
	}

	private static void setModifier(ServerPlayer player, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, Identifier id,
		double amount, AttributeModifier.Operation operation) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null) {
			instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, operation));
		}
	}

	private static void removeModifier(ServerPlayer player, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, Identifier id) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null) {
			instance.removeModifier(id);
		}
	}
}

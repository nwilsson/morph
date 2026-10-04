package com.noelwilsson.morph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/** Per-player morph data (synced attachments) and the server-side rules that apply a morph. */
public final class MorphState {
	/** Mobs this player has killed, so can morph into. Survives death. */
	public static final AttachmentType<List<Identifier>> UNLOCKED = AttachmentRegistry.<List<Identifier>>builder()
		.persistent(Identifier.CODEC.listOf())
		.copyOnDeath()
		.initializer(List::of)
		.syncWith(Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Morph.id("unlocked"));

	/** The mob the player currently is. Everyone needs it to draw the player. Lost on death. */
	public static final AttachmentType<Identifier> CURRENT = AttachmentRegistry.<Identifier>builder()
		.persistent(Identifier.CODEC)
		.syncWith(Identifier.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Morph.id("current"));

	private static final Identifier HEALTH_MODIFIER = Morph.id("health");
	private static final Identifier SPEED_MODIFIER = Morph.id("speed");
	private static final Identifier JUMP_MODIFIER = Morph.id("jump");
	private static final double PLAYER_BASE_HEALTH = 20.0;
	/** Ability effects are refreshed below this many ticks left, so they never run out while morphed. */
	private static final int EFFECT_REFRESH = 40;
	private static final int EFFECT_DURATION = 100;

	private MorphState() {}

	public static void init() {
		// Class loading registers the attachments.
	}

	public static @Nullable EntityType<?> current(Player player) {
		Identifier id = player.getAttached(CURRENT);
		return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
	}

	public static boolean canMorphInto(EntityType<?> type) {
		return type != EntityTypes.PLAYER && type != EntityTypes.MANNEQUIN && type != EntityTypes.ARMOR_STAND && DefaultAttributes.hasSupplier(type);
	}

	/** Returns true if this is a new unlock. */
	public static boolean unlock(ServerPlayer player, EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		List<Identifier> unlocked = player.getAttachedOrCreate(UNLOCKED);
		if (unlocked.contains(id)) {
			return false;
		}
		List<Identifier> updated = new ArrayList<>(unlocked);
		updated.add(id);
		player.setAttached(UNLOCKED, List.copyOf(updated));
		return true;
	}

	public static boolean isUnlocked(Player player, EntityType<?> type) {
		return player.getAttachedOrElse(UNLOCKED, List.of()).contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	public static void morph(ServerPlayer player, EntityType<?> type) {
		clearAbilities(player);
		player.setAttached(CURRENT, BuiltInRegistries.ENTITY_TYPE.getKey(type));
		player.refreshDimensions();
		apply(player);
		// Start at full mob health so morphing isn't a free heal or a death sentence.
		player.setHealth(player.getMaxHealth());
	}

	public static void unmorph(ServerPlayer player) {
		if (player.removeAttached(CURRENT) == null) {
			return;
		}
		clearAbilities(player);
		player.refreshDimensions();
		player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
	}

	/** Called every second for every player: (re)applies the morph so it survives relogs and gamemode changes. */
	public static void apply(ServerPlayer player) {
		EntityType<?> type = current(player);
		if (type == null) {
			return;
		}
		@SuppressWarnings("unchecked")
		double mobHealth = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type).getValue(Attributes.MAX_HEALTH);
		setModifier(player, Attributes.MAX_HEALTH, HEALTH_MODIFIER, mobHealth - PLAYER_BASE_HEALTH, AttributeModifier.Operation.ADD_VALUE);
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

package com.noelwilsson.morph.client;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphAnimationPayload;
import com.noelwilsson.morph.MorphFlapPayload;
import com.noelwilsson.morph.MorphPowerPayload;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws morphed players as their mob. Each morphed player gets a client-only "disguise" entity that copies the
 * player's position, rotation and movement and is ticked for its own animations (wing flaps, tentacles).
 */
public class MorphClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Morph.id("morph"));
	public static final KeyMapping OPEN_SIDEBAR = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.morph.sidebar", InputConstants.KEY_M, CATEGORY));
	public static final KeyMapping USE_POWER = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.morph.power", InputConstants.KEY_R, CATEGORY));

	private static final Map<UUID, Entity> DISGUISES = new HashMap<>();
	private static final Map<UUID, EntityType<?>> LAST_MORPH = new HashMap<>();
	/** The look each disguise was made with, so a new look rebuilds it. */
	private static final Map<UUID, MorphVariant> DISGUISE_LOOKS = new HashMap<>();
	/** The last swing copied onto each disguise. A new swing is a new object, so identity tells them apart. */
	private static final Map<UUID, LivingEntity.SwingDescription> LAST_SWING = new HashMap<>();
	/** Player tick until which the disguise looks aggressive (zombie arms up, vindicator axe out) after attacking. */
	private static final Map<UUID, Integer> AGGRESSIVE_UNTIL = new HashMap<>();
	private static final int AGGRESSIVE_TICKS = 40;
	private static boolean jumpWasDown;
	private static boolean glidingLastTick;
	/** Squid.handleEntityEvent: start the next tentacle stroke. */
	private static final byte SQUID_STROKE = 19;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(MorphClient::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Morph.id("power"), MorphPowerHud::extract);
		ClientPlayNetworking.registerGlobalReceiver(MorphAnimationPayload.TYPE, (payload, context) -> {
			if (context.client().level != null && context.client().level.getEntity(payload.playerId()) instanceof Player player) {
				animate(player, payload.event());
			}
		});
		Morph.LOGGER.info("Morph client loaded");
	}

	private static void tick(Minecraft minecraft) {
		while (OPEN_SIDEBAR.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new MorphSidebarScreen());
			}
		}
		flap(minecraft);
		while (USE_POWER.consumeClick()) {
			if (minecraft.player != null && MorphPowers.of(MorphState.current(minecraft.player)) != null
				&& ClientPlayNetworking.canSend(MorphPowerPayload.TYPE)) {
				ClientPlayNetworking.send(MorphPowerPayload.INSTANCE);
			}
		}
		ClientLevel level = minecraft.level;
		if (level == null) {
			DISGUISES.clear();
			LAST_MORPH.clear();
			DISGUISE_LOOKS.clear();
			LAST_SWING.clear();
			AGGRESSIVE_UNTIL.clear();
			GLIDES.clear();
			return;
		}
		for (Player player : level.players()) {
			EntityType<?> type = MorphState.current(player);
			// The attachment arrives by sync, so the client refreshes the hitbox itself when it changes.
			if (type != LAST_MORPH.get(player.getUUID())) {
				LAST_MORPH.put(player.getUUID(), type);
				player.refreshDimensions();
			}
			Entity disguise = disguise(player);
			if (disguise != null) {
				sync(player, disguise);
				swing(player, disguise);
				try {
					disguise.tick();
					// A client squid holds its tentacles still at the end of a stroke until the server says to go
					// again (entity event 19). Nobody sends that for a disguise, so send it here.
					if (disguise instanceof Squid squid && squid.tentacleMovement >= Mth.TWO_PI) {
						squid.handleEntityEvent(SQUID_STROKE);
					}
				} catch (RuntimeException e) {
					Morph.LOGGER.debug("Disguise tick failed for {}", disguise.getType(), e);
				}
				glide(player);
				sync(player, disguise);
				hold(player, disguise);
			}
		}
		DISGUISES.keySet().removeIf(uuid -> level.getPlayerByUUID(uuid) == null);
		DISGUISE_LOOKS.keySet().retainAll(DISGUISES.keySet());
		LAST_SWING.keySet().retainAll(DISGUISES.keySet());
		AGGRESSIVE_UNTIL.keySet().retainAll(DISGUISES.keySet());
		GLIDES.keySet().retainAll(DISGUISES.keySet());
	}

	/**
	 * Jump while already gliding is a wingbeat. Not the press that started the glide: vanilla spends that one on
	 * starting it, and it ends this tick already gliding, so only count presses made while gliding last tick.
	 */
	private static void flap(Minecraft minecraft) {
		Player player = minecraft.player;
		boolean jump = minecraft.options.keyJump.isDown();
		if (player != null && jump && !jumpWasDown && glidingLastTick && player.isFallFlying()
			&& ClientPlayNetworking.canSend(MorphFlapPayload.TYPE)) {
			ClientPlayNetworking.send(MorphFlapPayload.INSTANCE);
		}
		jumpWasDown = jump;
		glidingLastTick = player != null && player.isFallFlying();
	}

	/**
	 * How a gliding disguise is held, eased toward the flight once a tick (the renderer blends between ticks). The
	 * player model on an elytra points where you look; a winged mob should point where it's going and bank into turns.
	 */
	private static final class Glide {
		float pitch, pitchO, yaw, yawO, roll, rollO;

		Glide(Player player) {
			pitch = pitchO = player.getXRot();
			yaw = yawO = player.getYRot();
		}
	}

	private static final Map<UUID, Glide> GLIDES = new HashMap<>();
	/** Share of the way to the flight direction covered each tick. */
	private static final float GLIDE_EASE = 0.35F;
	private static final float BANK_EASE = 0.25F;
	private static final float MAX_PITCH = 80.0F;
	private static final float MAX_BANK = 60.0F;

	private static void glide(Player player) {
		if (!player.isFallFlying()) {
			GLIDES.remove(player.getUUID());
			return;
		}
		Glide glide = GLIDES.computeIfAbsent(player.getUUID(), uuid -> new Glide(player));
		glide.pitchO = glide.pitch;
		glide.yawO = glide.yaw;
		glide.rollO = glide.roll;
		Vec3 motion = player.getDeltaMovement();
		double horizontal = motion.horizontalDistance();
		float targetPitch = motion.lengthSqr() < 1.0E-4 ? player.getXRot()
			: Mth.clamp((float) -Math.toDegrees(Math.atan2(motion.y, horizontal)), -MAX_PITCH, MAX_PITCH);
		float targetYaw = horizontal < 0.05 ? player.getYRot() : (float) Math.toDegrees(Mth.atan2(motion.z, motion.x)) - 90.0F;
		// Bank: how far the view leads the flight, the same angle the vanilla elytra pose rolls by. Positive is a right turn.
		float targetRoll = 0.0F;
		Vec3 look = player.getLookAngle();
		if (horizontal > 0.05 && look.horizontalDistance() > 1.0E-3) {
			double dot = motion.horizontal().normalize().dot(look.horizontal().normalize());
			double side = motion.x * look.z - motion.z * look.x;
			targetRoll = Mth.clamp((float) (Math.signum(side) * Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(dot))))), -MAX_BANK, MAX_BANK);
		}
		glide.pitch += (targetPitch - glide.pitch) * GLIDE_EASE;
		glide.yaw += Mth.wrapDegrees(targetYaw - glide.yaw) * GLIDE_EASE;
		glide.roll += (targetRoll - glide.roll) * BANK_EASE;
	}

	/** Degrees a gliding disguise is banked, positive to the right; 0 for anything else. */
	public static float bank(Entity disguise, float partialTicks) {
		for (Map.Entry<UUID, Entity> entry : DISGUISES.entrySet()) {
			if (entry.getValue() == disguise) {
				Glide glide = GLIDES.get(entry.getKey());
				return glide == null ? 0.0F : Mth.lerp(partialTicks, glide.rollO, glide.roll);
			}
		}
		return 0.0F;
	}

	/** The disguise to draw for this player, or null if they aren't morphed. */
	public static @Nullable Entity disguise(Player player) {
		MorphVariant variant = MorphState.currentVariant(player);
		EntityType<?> type = variant == null ? null : variant.type();
		if (type == null) {
			DISGUISES.remove(player.getUUID());
			DISGUISE_LOOKS.remove(player.getUUID());
			return null;
		}
		Entity disguise = DISGUISES.get(player.getUUID());
		if (disguise == null || !variant.equals(DISGUISE_LOOKS.get(player.getUUID())) || disguise.level() != player.level()) {
			disguise = type.create(player.level(), EntitySpawnReason.LOAD);
			if (disguise == null) {
				return null;
			}
			try {
				MorphVariant.apply(disguise, variant.data());
			} catch (RuntimeException e) {
				Morph.LOGGER.warn("Couldn't apply {} to the {} disguise", variant.snbt(), type, e);
			}
			DISGUISE_LOOKS.put(player.getUUID(), variant);
			// 26.3 entities must have an ID before they render. Negative IDs never clash with real ones.
			disguise.setId(-1 - player.getId());
			disguise.setNoGravity(true);
			disguise.setSilent(true);
			disguise.setOldPosAndRot();
			DISGUISES.put(player.getUUID(), disguise);
		}
		return disguise;
	}

	/** Plays an entity event on the player's disguise, as if the server had sent it for a real mob. */
	public static void animate(Player player, byte event) {
		Entity disguise = disguise(player);
		if (disguise == null) {
			return;
		}
		sync(player, disguise);
		try {
			disguise.handleEntityEvent(event);
		} catch (RuntimeException e) {
			Morph.LOGGER.debug("Disguise event {} failed for {}", event, disguise.getType(), e);
		}
		AGGRESSIVE_UNTIL.put(player.getUUID(), player.tickCount + AGGRESSIVE_TICKS);
	}

	/**
	 * When the player starts a swing, the disguise starts its own: humanoid mobs (skeletons, piglins, vindicators)
	 * swing their arm, at the mob's own swing speed. Mobs without arms ignore it.
	 */
	private static void swing(Player player, Entity disguise) {
		LivingEntity.SwingDescription current = player.getCurrentSwing();
		if (current != null && current != LAST_SWING.get(player.getUUID()) && disguise instanceof LivingEntity living) {
			living.swing(current.hand(), current.animation(), false);
			AGGRESSIVE_UNTIL.put(player.getUUID(), player.tickCount + AGGRESSIVE_TICKS);
		}
		LAST_SWING.put(player.getUUID(), current);
		if (disguise instanceof Mob mob) {
			mob.setAggressive(player.tickCount < AGGRESSIVE_UNTIL.getOrDefault(player.getUUID(), 0));
		}
	}

	/**
	 * The disguise holds what the player holds, in the same hands. Mobs with arms (zombies, skeletons, piglins,
	 * illagers) draw it like a player would; foxes and dolphins carry it in their mouths; the rest ignore it.
	 */
	private static void hold(Player player, Entity disguise) {
		if (!(disguise instanceof LivingEntity living)) {
			return;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack held = player.getItemInHand(hand);
			if (!ItemStack.matches(held, living.getItemInHand(hand))) {
				living.setItemInHand(hand, held.copy());
			}
		}
		if (living instanceof Mob mob) {
			mob.setLeftHanded(player.getMainArm() == HumanoidArm.LEFT);
		}
	}

	/** Copy everything the renderer reads from the player onto the disguise. */
	public static void sync(Player player, Entity disguise) {
		disguise.setPos(player.getX(), player.getY(), player.getZ());
		// Animations run on age (blaze rods spin on tickCount + partialTick). Only commonTick() advances it and the
		// disguise is never in a level, so borrow the player's or the rods snap back every tick.
		disguise.tickCount = player.tickCount;
		disguise.xo = player.xo;
		disguise.yo = player.yo;
		disguise.zo = player.zo;
		disguise.xOld = player.xOld;
		disguise.yOld = player.yOld;
		disguise.zOld = player.zOld;
		disguise.setYRot(player.getYRot());
		disguise.yRotO = player.yRotO;
		disguise.setXRot(player.getXRot());
		disguise.xRotO = player.xRotO;
		disguise.setOnGround(player.onGround());
		disguise.setInvisible(player.isInvisible());
		disguise.setSharedFlagOnFire(player.isOnFire());
		disguise.setDeltaMovement(player.getDeltaMovement());
		if (disguise instanceof LivingEntity living) {
			living.yBodyRot = player.yBodyRot;
			living.yBodyRotO = player.yBodyRotO;
			living.yHeadRot = player.yHeadRot;
			living.yHeadRotO = player.yHeadRotO;
			living.hurtTime = player.hurtTime;
			living.hurtDuration = player.hurtDuration;
			living.deathTime = player.deathTime;
			Glide glide = GLIDES.get(player.getUUID());
			if (glide != null) {
				living.setXRot(glide.pitch);
				living.xRotO = glide.pitchO;
				living.setYRot(glide.yaw);
				living.yRotO = glide.yawO;
				living.yBodyRot = glide.yaw;
				living.yBodyRotO = glide.yawO;
				living.yHeadRot = glide.yaw;
				living.yHeadRotO = glide.yawO;
			}
		}
	}
}

package com.noelwilsson.morph.client;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphAnimationPayload;
import com.noelwilsson.morph.MorphBody;
import com.noelwilsson.morph.MorphFlapPayload;
import com.noelwilsson.morph.MorphPowerPayload;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphRules;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphTogglePayload;
import com.noelwilsson.morph.MorphUnlockPayload;
import com.noelwilsson.morph.MorphVariant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
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
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
	/** Back to yourself, or back into the last mob you were. Not G: 26.3 uses it for quick actions. */
	public static final KeyMapping TOGGLE = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.morph.toggle", InputConstants.KEY_B, CATEGORY));
	/** Hold for a ring of starred morphs, like iChun's Morph's favourites menu on the same key. */
	public static final KeyMapping RADIAL = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.morph.radial", InputConstants.KEY_GRAVE, CATEGORY));

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
		ClientPlayNetworking.registerGlobalReceiver(MorphUnlockPayload.TYPE, (payload, context) -> MorphUnlockToast.show(payload.variant()));
		Morph.LOGGER.info("Morph client loaded");
	}

	private static void tick(Minecraft minecraft) {
		while (OPEN_SIDEBAR.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new MorphSidebarScreen());
			}
		}
		while (RADIAL.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new MorphRadialScreen());
			}
		}
		flap(minecraft);
		while (USE_POWER.consumeClick()) {
			if (minecraft.player != null && MorphPowers.name(MorphState.current(minecraft.player), minecraft.player.level()) != null
				&& ClientPlayNetworking.canSend(MorphPowerPayload.TYPE)) {
				ClientPlayNetworking.send(MorphPowerPayload.INSTANCE);
			}
		}
		while (TOGGLE.consumeClick()) {
			if (minecraft.player != null && ClientPlayNetworking.canSend(MorphTogglePayload.TYPE)) {
				ClientPlayNetworking.send(MorphTogglePayload.INSTANCE);
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
			SEEN_LOOKS.clear();
			TRANSITIONS.clear();
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
		SEEN_LOOKS.keySet().removeIf(uuid -> level.getPlayerByUUID(uuid) == null);
		TRANSITIONS.keySet().retainAll(SEEN_LOOKS.keySet());
		TRANSITIONS.values().removeIf(transition -> level.getGameTime() - transition.start() > TRANSITION_TICKS);
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

	/** The disguise to draw for this player, or null if they aren't morphed. Notices when they change body. */
	public static @Nullable Entity disguise(Player player) {
		MorphVariant variant = MorphState.currentVariant(player);
		UUID uuid = player.getUUID();
		// Not seen before (just joined, just came into view) isn't a change, and neither is a sheep being sheared.
		MorphVariant seen = SEEN_LOOKS.get(uuid);
		boolean changed = SEEN_LOOKS.containsKey(uuid) && !Objects.equals(seen, variant) && (variant == null || !variant.sameBodyAs(seen));
		SEEN_LOOKS.put(uuid, variant);
		Entity before = DISGUISES.get(uuid);
		Entity disguise = makeDisguise(player, variant);
		if (changed) {
			startTransition(player, before, disguise);
		}
		return disguise;
	}

	private static @Nullable Entity makeDisguise(Player player, @Nullable MorphVariant variant) {
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

	/** A change of body being drawn: the old body shrinks away as the new one grows in. A null from is the player. */
	private record Transition(@Nullable Entity from, long start) {}

	/** The look each player was last drawn with (null: themselves). A player missing here hasn't been seen yet. */
	private static final Map<UUID, @Nullable MorphVariant> SEEN_LOOKS = new HashMap<>();
	private static final Map<UUID, Transition> TRANSITIONS = new HashMap<>();
	public static final int TRANSITION_TICKS = 15;
	/** Share of the transition the old body takes to vanish, and where the new one starts to grow. They overlap. */
	private static final float SHRINK_END = 0.6F;
	private static final float GROW_START = 0.4F;
	private static final float MIN_SCALE = 0.01F;

	private static void startTransition(Player player, @Nullable Entity from, @Nullable Entity to) {
		TRANSITIONS.put(player.getUUID(), new Transition(from, player.level().getGameTime()));
		// A puff of smoke around the bigger of the two bodies. Not for an invisible player, or into your own eyes.
		Minecraft minecraft = Minecraft.getInstance();
		boolean ownView = minecraft.getCameraEntity() == player && minecraft.options.getCameraType().isFirstPerson();
		if (player.isInvisible() || ownView) {
			return;
		}
		Entity fromBody = from == null ? player : from;
		Entity toBody = to == null ? player : to;
		for (Entity body : List.of(fromBody, toBody)) {
			if (body != player) {
				sync(player, body);
			}
		}
		Entity bigger = size(fromBody.getType()) >= size(toBody.getType()) ? fromBody : toBody;
		if (bigger instanceof LivingEntity living) {
			living.makePoofParticles();
		}
	}

	private static float size(EntityType<?> type) {
		return type.getWidth() * type.getWidth() * type.getHeight();
	}

	/** How far into its change of body this player is, 0 to 1, or -1 if they aren't changing. */
	public static float transition(Player player, float partialTicks) {
		Transition transition = TRANSITIONS.get(player.getUUID());
		if (transition == null) {
			return -1.0F;
		}
		float progress = (player.level().getGameTime() - transition.start() + partialTicks) / TRANSITION_TICKS;
		return progress >= 1.0F ? -1.0F : Math.max(progress, 0.0F);
	}

	/**
	 * What to draw for a player: their disguise (or themselves), with their name over it if morph:show_nametags is on,
	 * and while they change body the old body too, shrinking away. extractor turns an entity into its render state.
	 */
	public static List<EntityRenderState> extract(Player player, float partialTicks, Function<Entity, EntityRenderState> extractor) {
		Entity disguise = disguise(player);
		if (disguise != null) {
			sync(player, disguise);
		}
		EntityRenderState state = extractor.apply(disguise == null ? player : disguise);
		if (disguise != null && MorphRules.clientRules(player.level()).showNametags()) {
			EntityRenderState own = extractor.apply(player);
			state.nameTag = own.nameTag;
			state.nameTagAttachment = own.nameTagAttachment;
			state.scoreText = own.scoreText;
			state.isDiscrete = own.isDiscrete;
		}
		float progress = transition(player, partialTicks);
		Transition transition = TRANSITIONS.get(player.getUUID());
		if (progress < 0.0F || transition == null) {
			return List.of(state);
		}
		scale(state, ease((progress - GROW_START) / (1.0F - GROW_START)));
		float shrink = 1.0F - ease(progress / SHRINK_END);
		Entity from = transition.from() == null ? player : transition.from();
		if (shrink <= MIN_SCALE || from == (disguise == null ? player : disguise)) {
			return List.of(state);
		}
		if (from != player) {
			sync(player, from);
		}
		EntityRenderState old = extractor.apply(from);
		old.nameTag = null;
		old.scoreText = null;
		scale(old, shrink);
		return List.of(state, old);
	}

	/** Smoothstep, clamped: eases in and out of a change. */
	private static float ease(float t) {
		float x = Mth.clamp(t, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static void scale(EntityRenderState state, float scale) {
		float clamped = Math.max(scale, MIN_SCALE);
		state.shadowRadius *= clamped;
		if (state instanceof LivingEntityRenderState living) {
			living.scale *= clamped;
		}
	}

	/** Asks the server to morph into this look, or back to yourself for null. Goes through the commands, which check it. */
	public static void morphInto(@Nullable MorphVariant variant) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		if (variant == null) {
			minecraft.player.connection.sendCommand("unmorph");
		} else {
			minecraft.player.connection.sendCommand("morph " + variant.id() + (variant.isDefault() ? "" : " " + variant.snbt()));
		}
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
		// Worn armour, with morph:show_armor on. Only mobs whose renderer has armour layers draw it.
		boolean armor = MorphRules.clientRules(player.level()).showArmor();
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
				ItemStack worn = armor ? player.getItemBySlot(slot) : ItemStack.EMPTY;
				if (!ItemStack.matches(worn, living.getItemBySlot(slot))) {
					living.setItemSlot(slot, worn.copy());
				}
			}
		}
		// The saddle or harness others put on the body.
		ItemStack tack = MorphBody.tack(player);
		EquipmentSlot tackSlot = tack.isEmpty() ? null : MorphBody.tackSlot(disguise.getType(), tack);
		for (EquipmentSlot slot : List.of(EquipmentSlot.SADDLE, EquipmentSlot.BODY)) {
			ItemStack worn = slot == tackSlot ? tack : ItemStack.EMPTY;
			if (!ItemStack.matches(worn, living.getItemBySlot(slot))) {
				living.setItemSlot(slot, worn.copy());
			}
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

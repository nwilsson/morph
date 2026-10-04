package com.noelwilsson.morph.client;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphPowerPayload;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphState;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(MorphClient::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Morph.id("power"), MorphPowerHud::extract);
		Morph.LOGGER.info("Morph client loaded");
	}

	private static void tick(Minecraft minecraft) {
		while (OPEN_SIDEBAR.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new MorphSidebarScreen());
			}
		}
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
				try {
					disguise.tick();
				} catch (RuntimeException e) {
					Morph.LOGGER.debug("Disguise tick failed for {}", disguise.getType(), e);
				}
				sync(player, disguise);
			}
		}
		DISGUISES.keySet().removeIf(uuid -> level.getPlayerByUUID(uuid) == null);
	}

	/** The disguise to draw for this player, or null if they aren't morphed. */
	public static @Nullable Entity disguise(Player player) {
		EntityType<?> type = MorphState.current(player);
		if (type == null) {
			DISGUISES.remove(player.getUUID());
			return null;
		}
		Entity disguise = DISGUISES.get(player.getUUID());
		if (disguise == null || disguise.getType() != type || disguise.level() != player.level()) {
			disguise = type.create(player.level(), EntitySpawnReason.LOAD);
			if (disguise == null) {
				return null;
			}
			// 26.3 entities must have an ID before they render. Negative IDs never clash with real ones.
			disguise.setId(-1 - player.getId());
			disguise.setNoGravity(true);
			disguise.setSilent(true);
			disguise.setOldPosAndRot();
			DISGUISES.put(player.getUUID(), disguise);
		}
		return disguise;
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
		}
	}
}

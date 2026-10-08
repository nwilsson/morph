package com.noelwilsson.morph;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Morph implements ModInitializer {
	public static final String MOD_ID = "morph";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		MorphState.init();
		MorphPowers.init();
		MorphMobs.init();
		MorphRules.init();
		MorphBody.init();
		MorphLimits.init();
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> MorphTemplates.forgetServer());

		PayloadTypeRegistry.serverboundPlay().register(MorphPowerPayload.TYPE, MorphPowerPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MorphPowerPayload.TYPE, (payload, context) -> MorphPowers.use(context.player()));
		PayloadTypeRegistry.serverboundPlay().register(MorphFlapPayload.TYPE, MorphFlapPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MorphFlapPayload.TYPE, (payload, context) -> MorphPowers.flap(context.player()));
		PayloadTypeRegistry.serverboundPlay().register(MorphFavoritePayload.TYPE, MorphFavoritePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MorphFavoritePayload.TYPE,
			(payload, context) -> MorphState.toggleFavorite(context.player(), payload.variant()));
		PayloadTypeRegistry.serverboundPlay().register(MorphTogglePayload.TYPE, MorphTogglePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MorphTogglePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!player.isAlive() || player.isSpectator()) {
				return;
			}
			switch (MorphState.toggle(player)) {
				case NOTHING_TO_GO_BACK_TO -> player.sendOverlayMessage(Component.literal("No morph to go back to yet"));
				case STOPPED -> player.sendOverlayMessage(Component.literal("Something is stopping you changing body"));
				case CHANGED -> {
					EntityType<?> type = MorphState.current(player);
					player.sendOverlayMessage(type == null ? Component.literal("You're yourself again")
						: Component.literal("You are now a ").append(type.getDescription()));
				}
			}
		});
		PayloadTypeRegistry.clientboundPlay().register(MorphAnimationPayload.TYPE, MorphAnimationPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MorphUnlockPayload.TYPE, MorphUnlockPayload.CODEC);

		// Advancements for players who unlocked morphs before there were any.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> MorphProgress.update(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MorphSounds.forget(handler.player.getUUID()));

		// Attacking an entity plays the mob's own attack animation on the disguise.
		AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
			if (player instanceof ServerPlayer serverPlayer && !player.isSpectator() && MorphState.current(player) != null) {
				MorphAnimationPayload.broadcast(serverPlayer, MorphAnimationPayload.ATTACK);
			}
			return InteractionResult.PASS;
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			// Direct hits only: a fireball's owner is the player too, but that isn't a bite or a punch.
			if (!blocked && source.getDirectEntity() instanceof ServerPlayer player && source.getEntity() == player && entity != player) {
				MorphPowers.onMeleeHit(player, entity);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof Mob && source.getEntity() instanceof ServerPlayer player && MorphState.canMorphInto(entity.getType()))) {
				return;
			}
			MorphVariant variant = MorphVariant.of(entity);
			if (MorphState.isUnlocked(player, variant)) {
				return;
			}
			// morph:kills_to_unlock counts kills of a mob until it's known; after that, new looks unlock on one kill.
			int needed = MorphRules.killsToUnlock(player.level());
			if (needed > 1 && !MorphState.isUnlocked(player, entity.getType())) {
				int kills = MorphState.addKill(player, entity.getType());
				if (kills < needed) {
					player.sendOverlayMessage(Component.empty().append(entity.getType().getDescription())
						.append(": " + kills + "/" + needed + " kills to unlock"));
					return;
				}
			}
			if (!MorphState.unlock(player, variant)) {
				return; // another mod said no (MorphEvents.ALLOW_UNLOCK)
			}
			announceUnlock(player, variant);
			if (MorphRules.morphOnUnlock(player.level()) && player.isAlive()) {
				MorphState.morph(player, variant);
			}
		});

		// morph:keep_morphs_on_death off: a death forgets everything. Cleared off the dead body, so the copy-on-death
		// attachments have nothing to carry over to the respawned player.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player && !MorphRules.keepMorphsOnDeath(player.level())) {
				MorphState.forgetAll(player);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			server.getPlayerList().getPlayers().forEach(MorphState::tickWeaknesses);
			server.getPlayerList().getPlayers().forEach(MorphSounds::tick);
			if (server.getTickCount() % 20 == 0) {
				server.getPlayerList().getPlayers().forEach(MorphState::apply);
				server.getPlayerList().getPlayers().forEach(MorphLimits::tick);
			}
			MorphPowers.tick(server);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> MorphCommands.register(dispatcher, context));

		LOGGER.info("Morph loaded");
	}

	/** Tells the player about a new morph: a toast with the mob in it, or a chat line for clients that can't show one. */
	public static void announceUnlock(ServerPlayer player, MorphVariant variant) {
		if (ServerPlayNetworking.canSend(player, MorphUnlockPayload.TYPE)) {
			ServerPlayNetworking.send(player, new MorphUnlockPayload(variant));
		} else {
			EntityType<?> type = variant.type();
			String look = variant.describe(player.level());
			String command = "/morph " + variant.id().getPath() + (variant.isDefault() ? "" : " " + variant.snbt());
			player.sendSystemMessage(Component.literal("Unlocked morph: ")
				.append(type == null ? Component.literal(variant.id().toString()) : type.getDescription())
				.append(look.isEmpty() ? "" : " (" + look + ")")
				.append(Component.literal("  " + command).withStyle(ChatFormatting.GRAY)));
		}
		LOGGER.info("{} unlocked morph {} {}", player.getName().getString(), variant.id(), variant.snbt());
	}
}

package com.noelwilsson.morph;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
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

		PayloadTypeRegistry.serverboundPlay().register(MorphPowerPayload.TYPE, MorphPowerPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MorphPowerPayload.TYPE, (payload, context) -> MorphPowers.use(context.player()));
		PayloadTypeRegistry.serverboundPlay().register(MorphFlapPayload.TYPE, MorphFlapPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MorphFlapPayload.TYPE, (payload, context) -> MorphPowers.flap(context.player()));
		PayloadTypeRegistry.clientboundPlay().register(MorphAnimationPayload.TYPE, MorphAnimationPayload.CODEC);

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
			if (MorphState.unlock(player, variant)) {
				String look = variant.describe(player.level());
				String command = "/morph " + variant.id().getPath() + (variant.isDefault() ? "" : " " + variant.snbt());
				player.sendSystemMessage(Component.literal("Unlocked morph: ").append(entity.getType().getDescription())
					.append(look.isEmpty() ? "" : " (" + look + ")")
					.append(Component.literal("  " + command).withStyle(ChatFormatting.GRAY)));
				LOGGER.info("{} unlocked morph {} {}", player.getName().getString(), entity.getType(), variant.snbt());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			server.getPlayerList().getPlayers().forEach(MorphState::tickWeaknesses);
			if (server.getTickCount() % 20 == 0) {
				server.getPlayerList().getPlayers().forEach(MorphState::apply);
			}
			MorphPowers.tick(server);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> MorphCommands.register(dispatcher, context));

		LOGGER.info("Morph loaded");
	}
}

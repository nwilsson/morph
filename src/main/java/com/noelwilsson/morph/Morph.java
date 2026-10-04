package com.noelwilsson.morph;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Mob && source.getEntity() instanceof ServerPlayer player
				&& MorphState.canMorphInto(entity.getType()) && MorphState.unlock(player, entity.getType())) {
				player.sendSystemMessage(Component.literal("Unlocked morph: ").append(entity.getType().getDescription())
					.append(Component.literal("  /morph " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())
						.withStyle(ChatFormatting.GRAY)));
				LOGGER.info("{} unlocked morph {}", player.getName().getString(), entity.getType());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				server.getPlayerList().getPlayers().forEach(MorphState::apply);
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> MorphCommands.register(dispatcher, context));

		LOGGER.info("Morph loaded");
	}
}

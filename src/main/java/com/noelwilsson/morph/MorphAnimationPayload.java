package com.noelwilsson.morph;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEvent;

/**
 * Server to client: play this entity event on a morphed player's disguise. Real mobs start their attack animations
 * with an entity event (an iron golem's arm swing is event 4), but the event is sent for the player, who ignores it.
 */
public record MorphAnimationPayload(int playerId, byte event) implements CustomPacketPayload {
	/** Melee attack: warden, iron golem, ravager, hoglin, zoglin, creaking. Other mobs ignore it. */
	public static final byte ATTACK = EntityEvent.START_ATTACKING;
	/** The warden's sonic boom wind-up. */
	public static final byte SONIC_BOOM = EntityEvent.SONIC_CHARGE;

	public static final CustomPacketPayload.Type<MorphAnimationPayload> TYPE = new CustomPacketPayload.Type<>(Morph.id("animation"));
	public static final StreamCodec<ByteBuf, MorphAnimationPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, MorphAnimationPayload::playerId,
		ByteBufCodecs.BYTE, MorphAnimationPayload::event,
		MorphAnimationPayload::new);

	/** Sends the event to everyone who can see the player, and the player (for third person). */
	public static void broadcast(ServerPlayer player, byte event) {
		MorphAnimationPayload payload = new MorphAnimationPayload(player.getId(), event);
		for (ServerPlayer viewer : PlayerLookup.tracking(player)) {
			if (viewer != player && ServerPlayNetworking.canSend(viewer, TYPE)) {
				ServerPlayNetworking.send(viewer, payload);
			}
		}
		if (ServerPlayNetworking.canSend(player, TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

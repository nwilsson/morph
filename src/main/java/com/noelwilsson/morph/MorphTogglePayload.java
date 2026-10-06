package com.noelwilsson.morph;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: "I pressed the toggle key": back to yourself, or back into the last mob you were. */
public record MorphTogglePayload() implements CustomPacketPayload {
	public static final MorphTogglePayload INSTANCE = new MorphTogglePayload();
	public static final CustomPacketPayload.Type<MorphTogglePayload> TYPE = new CustomPacketPayload.Type<>(Morph.id("toggle"));
	public static final StreamCodec<ByteBuf, MorphTogglePayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

package com.noelwilsson.morph;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: "I pressed the power key". */
public record MorphPowerPayload() implements CustomPacketPayload {
	public static final MorphPowerPayload INSTANCE = new MorphPowerPayload();
	public static final CustomPacketPayload.Type<MorphPowerPayload> TYPE = new CustomPacketPayload.Type<>(Morph.id("use_power"));
	public static final StreamCodec<ByteBuf, MorphPowerPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

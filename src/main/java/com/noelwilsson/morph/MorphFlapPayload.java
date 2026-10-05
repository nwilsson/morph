package com.noelwilsson.morph;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: "I pressed jump while gliding", a wingbeat for gliding morphs. */
public record MorphFlapPayload() implements CustomPacketPayload {
	public static final MorphFlapPayload INSTANCE = new MorphFlapPayload();
	public static final CustomPacketPayload.Type<MorphFlapPayload> TYPE = new CustomPacketPayload.Type<>(Morph.id("flap"));
	public static final StreamCodec<ByteBuf, MorphFlapPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

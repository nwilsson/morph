package com.noelwilsson.morph;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: you just unlocked this look. The client shows a toast with the mob in it. */
public record MorphUnlockPayload(MorphVariant variant) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MorphUnlockPayload> TYPE = new CustomPacketPayload.Type<>(Morph.id("unlock"));
	public static final StreamCodec<ByteBuf, MorphUnlockPayload> CODEC = MorphVariant.STREAM_CODEC.map(MorphUnlockPayload::new,
		MorphUnlockPayload::variant);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

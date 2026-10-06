package com.noelwilsson.morph;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: "star (or unstar) this look" from the sidebar. */
public record MorphFavoritePayload(MorphVariant variant) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MorphFavoritePayload> TYPE = new CustomPacketPayload.Type<>(Morph.id("favorite"));
	public static final StreamCodec<ByteBuf, MorphFavoritePayload> CODEC = MorphVariant.STREAM_CODEC.map(MorphFavoritePayload::new,
		MorphFavoritePayload::variant);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

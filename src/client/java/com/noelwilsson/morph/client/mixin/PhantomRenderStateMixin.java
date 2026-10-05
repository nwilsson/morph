package com.noelwilsson.morph.client.mixin;

import com.noelwilsson.morph.client.BankedRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PhantomRenderState.class)
abstract class PhantomRenderStateMixin implements BankedRenderState {
	@Unique
	private float morph$bank;

	@Override
	public float morph$bank() {
		return morph$bank;
	}

	@Override
	public void morph$setBank(float degrees) {
		morph$bank = degrees;
	}
}

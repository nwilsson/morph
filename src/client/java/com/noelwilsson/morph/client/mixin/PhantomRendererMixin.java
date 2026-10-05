package com.noelwilsson.morph.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.noelwilsson.morph.client.BankedRenderState;
import com.noelwilsson.morph.client.MorphClient;
import net.minecraft.client.renderer.entity.PhantomRenderer;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A gliding phantom morph banks into its turns: rolled about its body after the renderer's own yaw and pitch. */
@Mixin(PhantomRenderer.class)
abstract class PhantomRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Phantom;Lnet/minecraft/client/renderer/entity/state/PhantomRenderState;F)V",
		at = @At("TAIL"))
	private void morph$extractBank(Phantom entity, PhantomRenderState state, float partialTicks, CallbackInfo ci) {
		((BankedRenderState) state).morph$setBank(MorphClient.bank(entity, partialTicks));
	}

	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/PhantomRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
		at = @At("TAIL"))
	private void morph$bank(PhantomRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		float bank = ((BankedRenderState) state).morph$bank();
		if (bank != 0.0F) {
			// The body faces local -Z here, so a right bank (right wing down) is a negative turn about +Z.
			poseStack.rotateDegrees(Axis.ZP, -bank);
		}
	}
}

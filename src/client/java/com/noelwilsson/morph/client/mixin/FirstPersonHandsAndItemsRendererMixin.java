package com.noelwilsson.morph.client.mixin;

import com.noelwilsson.morph.MorphState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A morphed player has no human arm to see in first person. Held items and maps still show. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class FirstPersonHandsAndItemsRendererMixin {
	/** The bare arm, when the hand is empty or holds a map. */
	@Inject(method = {"renderPlayerArm", "renderPlayerHand"}, at = @At("HEAD"), cancellable = true)
	private void morph$hideArm(CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && MorphState.current(minecraft.player) != null) {
			ci.cancel();
		}
	}
}

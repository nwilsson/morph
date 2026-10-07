package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets MorphSounds hear what a template plays instead of it playing (templates aren't anywhere). */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
	private void morph$catchTemplateSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
		if (MorphSounds.catchSound((Entity) (Object) this, sound, volume, pitch)) {
			ci.cancel();
		}
	}
}

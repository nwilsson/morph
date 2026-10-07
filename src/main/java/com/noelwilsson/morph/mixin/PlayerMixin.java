package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A morphed player's voice and footsteps are the mob's (MorphSounds). */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
	private void morph$hurtSound(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
		LivingEntity voice = MorphSounds.voice((Player) (Object) this);
		if (voice != null) {
			cir.setReturnValue(MorphSounds.hurtSound(voice, source));
		}
	}

	@Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
	private void morph$deathSound(CallbackInfoReturnable<SoundEvent> cir) {
		LivingEntity voice = MorphSounds.voice((Player) (Object) this);
		if (voice != null) {
			cir.setReturnValue(MorphSounds.deathSound(voice));
		}
	}

	/** A player's voice never varies; a mob's wobbles, and a baby's is higher. */
	@Inject(method = "getVoicePitch", at = @At("HEAD"), cancellable = true)
	private void morph$voicePitch(CallbackInfoReturnable<Float> cir) {
		LivingEntity voice = MorphSounds.voice((Player) (Object) this);
		if (voice != null) {
			cir.setReturnValue(voice.getVoicePitch());
		}
	}

	@Inject(method = "getFallSounds", at = @At("HEAD"), cancellable = true)
	private void morph$fallSounds(CallbackInfoReturnable<LivingEntity.Fallsounds> cir) {
		LivingEntity voice = MorphSounds.voice((Player) (Object) this);
		if (voice != null) {
			cir.setReturnValue(voice.getFallSounds());
		}
	}

	/** Steps on land are the mob's; in water the player's swimming sounds stay. */
	@Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
	private void morph$stepSound(BlockPos pos, BlockState state, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		LivingEntity voice = MorphSounds.voice(self);
		if (voice != null && !self.isInWater()) {
			ci.cancel();
			MorphSounds.step(self, voice, pos, state);
		}
	}
}

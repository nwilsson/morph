package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphSounds;
import com.noelwilsson.morph.MorphTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A morphed player's voice and footsteps are the mob's (MorphSounds), and blocks slow them like the mob. */
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

	/**
	 * Blocks that don't slow the mob don't slow the player: spiders walk through cobwebs. Asks the mob's own code
	 * by letting the template get stuck and seeing whether it did, so modded mobs' exceptions carry over.
	 */
	@Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
	private void morph$notStuck(BlockState state, Vec3 speedMultiplier, CallbackInfo ci) {
		LivingEntity body = MorphTemplates.body((Player) (Object) this);
		if (body != null) {
			EntityInvoker template = (EntityInvoker) body;
			template.morph$setStuckSpeedMultiplier(Vec3.ZERO);
			body.makeStuckInBlock(state, speedMultiplier);
			if (template.morph$stuckSpeedMultiplier().equals(Vec3.ZERO)) {
				ci.cancel();
			}
			template.morph$setStuckSpeedMultiplier(Vec3.ZERO);
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

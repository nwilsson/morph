package com.noelwilsson.morph.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A mob's own sounds, asked of a template for a morphed player (MorphSounds). */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {
	@Invoker("getHurtSound")
	@Nullable SoundEvent morph$getHurtSound(DamageSource source);

	@Invoker("getDeathSound")
	@Nullable SoundEvent morph$getDeathSound();

	@Invoker("getSoundVolume")
	float morph$getSoundVolume();
}

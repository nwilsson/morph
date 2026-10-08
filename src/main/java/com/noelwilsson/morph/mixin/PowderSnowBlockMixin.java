package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphTemplates;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs light enough to walk on powder snow (rabbits, foxes, endermites) carry a morphed player over it too. */
@Mixin(PowderSnowBlock.class)
abstract class PowderSnowBlockMixin {
	@Inject(method = "canEntityWalkOnPowderSnow", at = @At("HEAD"), cancellable = true)
	private static void morph$walkOnSnow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof Player player) {
			LivingEntity body = MorphTemplates.body(player);
			if (body != null && PowderSnowBlock.canEntityWalkOnPowderSnow(body)) {
				cir.setReturnValue(true);
			}
		}
	}
}

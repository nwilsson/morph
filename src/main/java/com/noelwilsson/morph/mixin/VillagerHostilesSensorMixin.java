package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphRelations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Villagers panic at players who look like a zombie, an illager or anything else they fear, from the same distance. */
@Mixin(VillagerHostilesSensor.class)
abstract class VillagerHostilesSensorMixin {
	@Inject(method = "isMatchingEntity", at = @At("HEAD"), cancellable = true)
	private void morph$fearDisguise(ServerLevel level, LivingEntity body, LivingEntity mob, CallbackInfoReturnable<Boolean> cir) {
		Float distance = MorphRelations.villagersFear(mob);
		if (distance != null) {
			cir.setReturnValue(mob.distanceToSqr(body) <= distance * distance);
		}
	}
}

package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphAbilities;
import com.noelwilsson.morph.MorphState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spider morphs climb walls: pushing into a wall counts as being on a ladder. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
	private void morph$climbWalls(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player player && player.horizontalCollision && !player.isSpectator()) {
			EntityType<?> type = MorphState.current(player);
			if (type != null && MorphAbilities.of(type).contains(MorphAbilities.Ability.CLIMB)) {
				cir.setReturnValue(true);
			}
		}
	}
}

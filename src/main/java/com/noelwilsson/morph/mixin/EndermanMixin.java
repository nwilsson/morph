package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphRelations;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An enderman doesn't mind being looked at by another enderman, any more than by someone in a carved pumpkin. */
@Mixin(Enderman.class)
abstract class EndermanMixin {
	@Inject(method = "isBeingStaredBy", at = @At("HEAD"), cancellable = true)
	private void morph$endermenLookAtEachOther(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (MorphRelations.isFellowEnderman(player)) {
			cir.setReturnValue(false);
		}
	}
}

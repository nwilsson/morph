package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphRelations;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Phantoms break off a swoop when a cat is near, and a cat-morphed player counts (checked as often as cats are). */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomSweepAttackGoal")
abstract class PhantomSweepAttackGoalMixin {
	@Shadow
	@Final
	Phantom this$0;

	@Shadow
	private boolean isScaredOfCat;

	@Inject(method = "canContinueToUse", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/Phantom$PhantomSweepAttackGoal;isScaredOfCat:Z",
		opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void morph$scaredOfCatPlayers(CallbackInfoReturnable<Boolean> cir) {
		if (!isScaredOfCat && MorphRelations.catPlayerNear(this$0)) {
			isScaredOfCat = true;
		}
	}
}

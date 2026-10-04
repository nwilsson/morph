package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphRelations;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iron golems hunt monster-morphed players like the monsters they look like (vanilla's own monster goal is Mob-only). */
@Mixin(IronGolem.class)
abstract class IronGolemMixin {
	@Inject(method = "registerGoals", at = @At("TAIL"))
	private void morph$huntMonsterPlayers(CallbackInfo ci) {
		IronGolem golem = (IronGolem) (Object) this;
		((MobAccessor) golem).morph$targetSelector().addGoal(3,
			new NearestAttackableTargetGoal<>(golem, Player.class, 5, false, false, (target, level) -> MorphRelations.golemHunts(target)));
	}
}

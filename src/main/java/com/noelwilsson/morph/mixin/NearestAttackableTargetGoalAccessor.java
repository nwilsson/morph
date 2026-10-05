package com.noelwilsson.morph.mixin;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NearestAttackableTargetGoal.class)
public interface NearestAttackableTargetGoalAccessor {
	/** The kind of mob this goal hunts (Player, AbstractVillager, IronGolem, ...). */
	@Accessor("targetType")
	Class<?> morph$targetType();
}

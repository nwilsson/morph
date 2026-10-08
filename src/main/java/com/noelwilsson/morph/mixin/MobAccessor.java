package com.noelwilsson.morph.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("goalSelector")
	GoalSelector morph$goalSelector();

	@Accessor("targetSelector")
	GoalSelector morph$targetSelector();

	/** The mob's own idle sound (wolves and cats pick theirs from their sound variant). */
	@Invoker("getAmbientSound")
	@Nullable SoundEvent morph$ambientSound();

	/** What the mob does when a player uses an item on it: a cow fills a bucket, a sheep is sheared. */
	@Invoker("mobInteract")
	InteractionResult morph$mobInteract(Player player, InteractionHand hand);
}

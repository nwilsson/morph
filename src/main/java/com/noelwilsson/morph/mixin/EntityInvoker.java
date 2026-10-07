package com.noelwilsson.morph.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityInvoker {
	@Invoker("setSharedFlag")
	void morph$setSharedFlag(int flag, boolean value);

	@Invoker("playStepSound")
	void morph$playStepSound(BlockPos pos, BlockState state);
}

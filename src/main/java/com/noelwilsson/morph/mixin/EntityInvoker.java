package com.noelwilsson.morph.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityInvoker {
	@Invoker("setSharedFlag")
	void morph$setSharedFlag(int flag, boolean value);

	@Invoker("playStepSound")
	void morph$playStepSound(BlockPos pos, BlockState state);

	@Invoker("getPassengerAttachmentPoint")
	Vec3 morph$passengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale);

	@Accessor("stuckSpeedMultiplier")
	Vec3 morph$stuckSpeedMultiplier();

	@Accessor("stuckSpeedMultiplier")
	void morph$setStuckSpeedMultiplier(Vec3 multiplier);
}

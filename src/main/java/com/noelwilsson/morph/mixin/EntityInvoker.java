package com.noelwilsson.morph.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityInvoker {
	@Invoker("setSharedFlag")
	void morph$setSharedFlag(int flag, boolean value);
}

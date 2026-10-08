package com.noelwilsson.morph.mixin;

import com.google.common.collect.ImmutableMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VillagerHostilesSensor.class)
public interface VillagerHostilesSensorAccessor {
	/** How close each mob villagers fear may come before they run, by mob. */
	@Accessor("ACCEPTABLE_DISTANCE_FROM_HOSTILES")
	static ImmutableMap<EntityType<?>, Float> morph$fearDistances() {
		throw new AssertionError();
	}
}

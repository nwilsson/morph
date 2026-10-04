package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A morphed player gets the mob's hitbox and eye height, in every pose. */
@Mixin(Avatar.class)
abstract class AvatarMixin {
	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void morph$mobDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if ((Object) this instanceof Player player && pose != Pose.SLEEPING) {
			EntityType<?> type = MorphState.current(player);
			if (type != null) {
				cir.setReturnValue(type.getDimensions());
			}
		}
	}
}

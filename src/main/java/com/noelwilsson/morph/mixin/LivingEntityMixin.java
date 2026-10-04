package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphAbilities;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphTemplates;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mob traits that vanilla already checks on every LivingEntity, answered for a morphed player by the mob itself. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	private @Nullable EntityType<?> morph$type() {
		return (Object) this instanceof Player player ? MorphState.current(player) : null;
	}

	/** Spider morphs climb walls: pushing into a wall counts as being on a ladder. */
	@Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
	private void morph$climbWalls(CallbackInfoReturnable<Boolean> cir) {
		EntityType<?> type = morph$type();
		if (type != null && ((Player) (Object) this).horizontalCollision && !((Player) (Object) this).isSpectator()
			&& MorphAbilities.of(type).contains(MorphAbilities.Ability.CLIMB)) {
			cir.setReturnValue(true);
		}
	}

	/** Blazes, endermen, striders and snow golems take damage in water and rain (LivingEntity.aiStep). */
	@Inject(method = "isSensitiveToWater", at = @At("HEAD"), cancellable = true)
	private void morph$waterHurts(CallbackInfoReturnable<Boolean> cir) {
		EntityType<?> type = morph$type();
		if (type != null) {
			LivingEntity template = MorphTemplates.get(type, ((LivingEntity) (Object) this).level());
			if (template != null) {
				cir.setReturnValue(template.isSensitiveToWater());
			}
		}
	}

	/** Undead morphs are healed by harming and hurt by healing. */
	@Inject(method = "isInvertedHealAndHarm", at = @At("HEAD"), cancellable = true)
	private void morph$undead(CallbackInfoReturnable<Boolean> cir) {
		EntityType<?> type = morph$type();
		if (type != null) {
			LivingEntity template = MorphTemplates.get(type, ((LivingEntity) (Object) this).level());
			if (template != null) {
				cir.setReturnValue(template.isInvertedHealAndHarm());
			}
		}
	}

	/** Strider morphs walk on lava. */
	@Inject(method = "canStandOnFluid", at = @At("HEAD"), cancellable = true)
	private void morph$lavaWalk(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		EntityType<?> type = morph$type();
		if (type != null && fluid.is(FluidTags.LAVA) && MorphAbilities.of(type).contains(MorphAbilities.Ability.LAVA_WALK)) {
			cir.setReturnValue(true);
		}
	}
}

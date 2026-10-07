package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphAbilities;
import com.noelwilsson.morph.MorphRelations;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphTemplates;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mob traits that vanilla already checks on every LivingEntity, answered for a morphed player by the mob itself. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	/** Strider.getLiquidCollisionShape: a half-block slab at the lava surface. */
	private static final VoxelShape STRIDER_LIQUID_SHAPE = Block.column(16.0, 0.0, 8.0);
	/** Entity.FLAG_FALL_FLYING. */
	private static final int FALL_FLYING_FLAG = 7;

	private @Nullable EntityType<?> morph$type() {
		return (Object) this instanceof Player player ? MorphState.current(player) : null;
	}

	@Shadow
	protected abstract boolean canGlide();

	private boolean morph$glides() {
		EntityType<?> type = morph$type();
		return type != null && MorphAbilities.of(type).contains(MorphAbilities.Ability.GLIDE);
	}

	/** Phantom morphs glide like an elytra wearer, without the elytra: same rules for when (in the air, not riding). */
	@Inject(method = "canGlide", at = @At("HEAD"), cancellable = true)
	private void morph$wings(CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (morph$glides() && !self.onGround() && !self.isPassenger() && !self.hasEffect(MobEffects.LEVITATION)) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * Wings don't wear out. Vanilla damages a random elytra every second of gliding and, wearing none, would pick from
	 * an empty list. So without an elytra on, run the rest of updateFallFlying here and skip the wear.
	 */
	@Inject(method = "updateFallFlying", at = @At("HEAD"), cancellable = true)
	private void morph$glideWithoutElytra(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!morph$glides()) {
			return;
		}
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (LivingEntity.canGlideUsing(self.getItemBySlot(slot), slot)) {
				return;
			}
		}
		ci.cancel();
		self.checkFallDistanceAccumulation();
		if (!self.level().isClientSide()) {
			if (!canGlide()) {
				((EntityInvoker) self).morph$setSharedFlag(FALL_FLYING_FLAG, false);
			} else if ((self.getFallFlyingTicks() + 1) % 10 == 0) {
				self.gameEvent(GameEvent.ELYTRA_GLIDE);
			}
		}
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

	private boolean morph$lavaWalker() {
		EntityType<?> type = morph$type();
		return type != null && MorphAbilities.of(type).contains(MorphAbilities.Ability.LAVA_WALK);
	}

	/**
	 * Strider morphs walk on lava. LiquidBlock only becomes solid for an entity that both can stand on the fluid and
	 * has a liquid collision shape, so both are needed (Strider overrides both).
	 */
	@Inject(method = "canStandOnFluid", at = @At("HEAD"), cancellable = true)
	private void morph$lavaWalk(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		if (fluid.is(FluidTags.LAVA) && morph$lavaWalker()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getLiquidCollisionShape", at = @At("HEAD"), cancellable = true)
	private void morph$lavaSurface(CallbackInfoReturnable<VoxelShape> cir) {
		if (morph$lavaWalker()) {
			cir.setReturnValue(STRIDER_LIQUID_SHAPE);
		}
	}

	/**
	 * Strider.floatStrider: sunk into lava, rise back to the surface. Runs on the client too, which moves the player.
	 * Vanilla's +0.05 loses to player gravity (settles at -0.06/tick), so lift harder: about 1.5 blocks a second.
	 */
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void morph$floatOnLava(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (morph$lavaWalker() && self.isInLava()) {
			CollisionContext context = CollisionContext.of(self);
			if (context.isAbove(STRIDER_LIQUID_SHAPE, self.blockPosition(), true)
				&& !self.level().getFluidState(self.blockPosition().above()).is(FluidTags.LAVA)) {
				self.setOnGround(true);
			} else {
				Vec3 motion = self.getDeltaMovement();
				self.setDeltaMovement(motion.x * 0.5, Math.max(motion.y * 0.5 + 0.05, 0.16), motion.z * 0.5);
			}
		}
	}

	/** Monsters leave morphed players alone unless they hunt that mob or were hit (covers goal and brain AI: both ask canAttack). */
	@Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
	private void morph$monstersIgnore(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (MorphRelations.monsterIgnores((LivingEntity) (Object) this, target)) {
			cir.setReturnValue(false);
		}
	}

	/** monster_behavior SHORT_RANGE: monsters the disguise would fool notice the player from closer (TargetingConditions). */
	@Inject(method = "getVisibilityPercent", at = @At("RETURN"), cancellable = true)
	private void morph$seenUpClose(ServerLevel level, @Nullable Entity targetingEntity, CallbackInfoReturnable<Double> cir) {
		double visibility = MorphRelations.visibility((LivingEntity) (Object) this, targetingEntity);
		if (visibility < 1.0) {
			cir.setReturnValue(cir.getReturnValue() * visibility);
		}
	}
}

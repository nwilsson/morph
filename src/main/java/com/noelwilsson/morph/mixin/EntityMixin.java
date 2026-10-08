package com.noelwilsson.morph.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.noelwilsson.morph.MorphBody;
import com.noelwilsson.morph.MorphSounds;
import com.noelwilsson.morph.MorphTemplates;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets MorphSounds hear what a template plays instead of it playing (templates aren't anywhere); lightning charges
 * creepers; saddled morphs can be ridden.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
	private void morph$catchTemplateSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
		if (MorphSounds.catchSound((Entity) (Object) this, sound, volume, pitch)) {
			ci.cancel();
		}
	}

	@Inject(method = "thunderHit", at = @At("TAIL"))
	private void morph$charge(ServerLevel level, LightningBolt lightning, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player) {
			MorphBody.struckByLightning(player);
		}
	}

	/**
	 * Vanilla lets nothing ride an entity that can't be saved, which players can't. A saddled horse-morphed player can
	 * carry a rider anyway: a rider who logs out is taken off first (PlayerListMixin), so nothing saves the ride.
	 */
	@ModifyExpressionValue(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z"))
	private boolean morph$saddledPlayersCarry(boolean canSerialize, @Local(argsOnly = true) Entity vehicle) {
		return canSerialize || vehicle instanceof Player player && MorphBody.canCarry(player);
	}

	/** Riders sit where they'd sit on the mob: further back on a camel, higher on a horse. */
	@Inject(method = "getPassengerAttachmentPoint", at = @At("HEAD"), cancellable = true)
	private void morph$seat(Entity passenger, EntityDimensions dimensions, float scale, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this instanceof Player player) {
			LivingEntity body = MorphTemplates.body(player);
			if (body != null) {
				body.setYRot(player.getYRot());
				cir.setReturnValue(((EntityInvoker) body).morph$passengerAttachmentPoint(passenger, body.getDimensions(body.getPose()), scale));
			}
		}
	}

	/**
	 * Vanilla tells everyone watching an entity when its riders change, but a player doesn't watch themselves, so a
	 * ridden player's own game never learns it carries anyone and draws the rider frozen where they got on. Tell them.
	 */
	@Inject(method = {"addPassenger", "removePassenger"}, at = @At("TAIL"))
	private void morph$tellMountAboutRiders(Entity passenger, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer mount) {
			mount.connection.send(new ClientboundSetPassengersPacket(mount));
		}
	}
}

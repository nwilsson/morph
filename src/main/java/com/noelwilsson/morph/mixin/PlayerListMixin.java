package com.noelwilsson.morph.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player riding a morphed player gets off before logging out. Vanilla saves a rider's mount with them and unloads
 * it, which would take the other player out of the game.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
	@Inject(method = "remove", at = @At("HEAD"))
	private void morph$getOffBeforeLeaving(ServerPlayer player, CallbackInfo ci) {
		if (player.getRootVehicle() instanceof Player) {
			player.stopRiding();
		}
	}
}

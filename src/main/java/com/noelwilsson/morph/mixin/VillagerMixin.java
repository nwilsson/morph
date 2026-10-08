package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphRelations;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Villagers won't trade with a player who looks like something they run from: they shake their head, like a baby does. */
@Mixin(Villager.class)
abstract class VillagerMixin {
	@Shadow
	private void setUnhappy() {
		throw new AssertionError();
	}

	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void morph$noTradeWithMonsters(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (MorphRelations.villagersFear(player) != null) {
			if (hand == InteractionHand.MAIN_HAND) {
				setUnhappy();
			}
			cir.setReturnValue(InteractionResult.SUCCESS);
		}
	}
}

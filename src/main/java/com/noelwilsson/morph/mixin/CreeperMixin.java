package com.noelwilsson.morph.mixin;

import com.noelwilsson.morph.MorphRelations;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Creepers flee cat- and ocelot-morphed players, with the same goal and numbers they use for real cats. */
@Mixin(Creeper.class)
abstract class CreeperMixin {
	@Inject(method = "registerGoals", at = @At("TAIL"))
	private void morph$fleeCatPlayers(CallbackInfo ci) {
		Creeper creeper = (Creeper) (Object) this;
		((MobAccessor) creeper).morph$goalSelector().addGoal(3,
			new AvoidEntityGoal<>(creeper, Player.class, MorphRelations::scaresCreepers, 6.0F, 1.0, 1.2, e -> true));
	}
}

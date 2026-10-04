package com.noelwilsson.morph.client.mixin;

import com.noelwilsson.morph.client.MorphClient;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When the world's entity list is extracted, a morphed player is drawn as their disguise. Only this call is
 * swapped: the local player's own state (first-person hands, overlays) must stay an AvatarRenderState.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
	@Shadow
	private EntityRenderState extractEntity(Entity entity, float partialTickTime) {
		throw new AssertionError();
	}

	@Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState morph$drawAsMob(LevelExtractor self, Entity entity, float partialTicks) {
		if (entity instanceof Player player) {
			Entity disguise = MorphClient.disguise(player);
			if (disguise != null) {
				MorphClient.sync(player, disguise);
				return this.extractEntity(disguise, partialTicks);
			}
		}
		return this.extractEntity(entity, partialTicks);
	}
}

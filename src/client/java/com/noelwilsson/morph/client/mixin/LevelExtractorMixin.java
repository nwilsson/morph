package com.noelwilsson.morph.client.mixin;

import com.noelwilsson.morph.client.MorphClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When the world's entity list is extracted, a morphed player is drawn as their disguise. Only this call is
 * swapped: the local player's own state (first-person hands, overlays) must stay an AvatarRenderState. While a player
 * changes body, their old body is drawn too, added to the list right after the new one.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
	/** States to add after the one being added now: a changing player's old body. */
	@Unique
	private final List<EntityRenderState> morph$extra = new ArrayList<>();

	@Shadow
	private EntityRenderState extractEntity(Entity entity, float partialTickTime) {
		throw new AssertionError();
	}

	@Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState morph$drawAsMob(LevelExtractor self, Entity entity, float partialTicks) {
		morph$extra.clear();
		if (entity instanceof Player player) {
			List<EntityRenderState> states = MorphClient.extract(player, partialTicks, e -> this.extractEntity(e, partialTicks));
			morph$extra.addAll(states.subList(1, states.size()));
			return states.getFirst();
		}
		return this.extractEntity(entity, partialTicks);
	}

	@Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private boolean morph$addOldBody(List<Object> states, Object state) {
		states.add(state);
		states.addAll(morph$extra);
		morph$extra.clear();
		return true;
	}
}

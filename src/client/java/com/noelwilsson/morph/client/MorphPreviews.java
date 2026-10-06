package com.noelwilsson.morph.client;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Little 3D mobs for the sidebar and the radial menu: made once per screen, drawn standing in a box. */
public final class MorphPreviews {
	/** Icons stand turned a little, so you see the side of the mob as well as its face. */
	public static final float ICON_YAW = (float) Math.toRadians(-30);
	/** Looking down on the mob a little. */
	private static final float TILT = (float) Math.toRadians(-12);

	private MorphPreviews() {}

	/** A client-only model of this look, or null if the mob can't be made (the icon shows instead). */
	public static @Nullable LivingEntity model(EntityType<?> type, MorphVariant variant, int id) {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			LivingEntity entity = MorphVariant.create(type, variant.data(), minecraft.level);
			if (entity != null) {
				entity.setId(id);
			}
			return entity;
		} catch (RuntimeException e) {
			Morph.LOGGER.debug("Couldn't make a preview model of {}", variant.snbt(), e);
			return null;
		}
	}

	/** The mob's spawn egg, for when its model can't be drawn. */
	public static ItemStack icon(EntityType<?> type) {
		return SpawnEggItem.byId(type).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.BARRIER));
	}

	/** Draws the mob standing in the box, scaled to fit, turned by yaw. Falls back to the icon. */
	public static void extract(GuiGraphicsExtractor graphics, @Nullable LivingEntity model, ItemStack icon, int x0, int y0, int x1, int y1,
		float yaw) {
		Minecraft minecraft = Minecraft.getInstance();
		EntityRenderState state = null;
		if (model != null) {
			try {
				state = minecraft.getEntityRenderDispatcher().getRenderer(model).createRenderState(model, 1.0F);
			} catch (RuntimeException e) {
				Morph.LOGGER.debug("Couldn't draw a preview model of {}", model.getType(), e);
			}
		}
		if (state == null) {
			graphics.item(icon, (x0 + x1) / 2 - 8, (y0 + y1) / 2 - 8);
			return;
		}
		state.shadowPieces.clear();
		state.outlineColor = 0;
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180.0F;
			living.yRot = 0.0F;
			living.xRot = 0.0F;
			living.boundingBoxWidth /= living.scale;
			living.boundingBoxHeight /= living.scale;
			living.scale = 1.0F;
		}
		float boxWidth = state.boundingBoxWidth;
		float boxHeight = state.boundingBoxHeight;
		// A morphed player's hitbox is the mob's; the model drawn is still a player.
		if (model instanceof Player) {
			boxWidth = EntityTypes.PLAYER.getWidth();
			boxHeight = EntityTypes.PLAYER.getHeight();
		}
		// Turning, the corners of the hitbox sweep out its diagonal.
		float size = Math.min((y1 - y0) * 0.85F / Math.max(boxHeight, 0.1F), (x1 - x0) * 0.9F / Math.max(boxWidth * Mth.SQRT_OF_TWO, 0.1F));
		Quaternionf tilt = new Quaternionf().rotateX(TILT);
		Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(tilt).rotateY(yaw);
		graphics.entity(state, size, new Vector3f(0.0F, boxHeight / 2.0F, 0.0F), rotation, tilt, x0, y0, x1, y1);
	}
}

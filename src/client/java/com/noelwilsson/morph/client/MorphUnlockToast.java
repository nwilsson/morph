package com.noelwilsson.morph.client;

import com.noelwilsson.morph.MorphTemplates;
import com.noelwilsson.morph.MorphVariant;
import com.noelwilsson.morph.mixin.MobAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/** "Morph unlocked" with the mob turning in the corner, in place of the old chat line. The mob says hello. */
public class MorphUnlockToast implements Toast {
	private static final Identifier BACKGROUND_SPRITE = Identifier.withDefaultNamespace("toast/advancement");
	private static final long DISPLAY_TIME = 5000;
	/** Entity ID for the toast's model, away from the sidebar's and the disguises'. */
	private static final int MODEL_ID = Integer.MIN_VALUE / 4;
	private static final int TITLE = 0xFFFFFF00;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int SUBTEXT = 0xFFB0B8C0;

	private final MorphVariant variant;
	private final Component name;
	private final String look;
	private final @Nullable LivingEntity model;
	private final ItemStack icon;
	private final @Nullable SoundEvent voice;
	private Toast.Visibility wantedVisibility = Toast.Visibility.HIDE;
	private boolean spoke;

	public MorphUnlockToast(EntityType<?> type, MorphVariant variant) {
		Minecraft minecraft = Minecraft.getInstance();
		this.variant = variant;
		name = type.getDescription();
		look = variant.describe(minecraft.level);
		model = MorphPreviews.model(type, variant, MODEL_ID);
		icon = MorphPreviews.icon(type);
		voice = MorphTemplates.get(variant, minecraft.level) instanceof MobAccessor mob ? mob.morph$ambientSound() : null;
	}

	public static void show(MorphVariant variant) {
		EntityType<?> type = variant.type();
		if (type != null) {
			Minecraft.getInstance().gui.toastManager().addToast(new MorphUnlockToast(type, variant));
		}
	}

	public MorphVariant variant() {
		return variant;
	}

	@Override
	public Toast.Visibility getWantedVisibility() {
		return wantedVisibility;
	}

	@Override
	public void update(ToastManager manager, long fullyVisibleForMs) {
		wantedVisibility = fullyVisibleForMs >= DISPLAY_TIME * manager.getNotificationDisplayTimeMultiplier() ? Toast.Visibility.HIDE
			: Toast.Visibility.SHOW;
		// The mob's own voice once it's in view, so you hear what you unlocked.
		if (!spoke && voice != null) {
			spoke = true;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(voice, 1.0F, 0.8F));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), height());
		graphics.text(font, Component.translatable("toast.morph.unlocked"), 30, look.isEmpty() ? 7 : 4, TITLE, false);
		graphics.text(font, font.plainSubstrByWidth(name.getString(), width() - 36), 30, look.isEmpty() ? 18 : 13, TEXT, false);
		if (!look.isEmpty()) {
			graphics.text(font, font.plainSubstrByWidth(look, width() - 36), 30, 22, SUBTEXT, false);
		}
		if (model == null) {
			graphics.fakeItem(icon, 8, 8);
			return;
		}
		// Models are drawn in screen space, not through the toast's sliding transform, so place the box by hand.
		Vector2f corner = graphics.pose().transformPosition(new Vector2f(4, 4));
		int x = Math.round(corner.x);
		int y = Math.round(corner.y);
		float yaw = MorphPreviews.ICON_YAW + fullyVisibleForMs / 1000.0F;
		MorphPreviews.extract(graphics, model, icon, x, y, x + 24, y + 24, yaw);
	}
}

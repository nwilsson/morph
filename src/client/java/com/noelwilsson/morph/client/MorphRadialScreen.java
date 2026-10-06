package com.noelwilsson.morph.client;

import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Hold the radial key: your starred morphs in a ring around the cursor, yourself at the top. Point at one and let go
 * to become it; let go in the middle to cancel. A quick tap leaves the ring open, then a click picks.
 */
public class MorphRadialScreen extends Screen {
	/** Starred looks shown; more than this and the ring gets too crowded to point at. */
	public static final int MAX_FAVORITES = 15;
	public static final int SLOT = 32;
	/** Inside this distance from the middle, nothing is picked. */
	public static final int DEAD_ZONE = 20;
	/** A press shorter than this is a tap: the ring stays open for a click. */
	private static final long TAP_MILLIS = 250;
	private static final float SPIN_SECONDS = 6;
	/** Room kept under the ring for the hotbar and the hearts above it. */
	private static final int HOTBAR_CLEARANCE = 40;
	private static final int MODEL_ID_BASE = Integer.MIN_VALUE / 2 + 100_000;

	private static final int SLOT_BACK = 0x90101418;
	private static final int SLOT_HOVER = 0xC0404C58;
	private static final int BORDER = 0xFF3A4450;
	private static final int SELECTED_EDGE = 0xFF50D070;
	private static final int HOVER_EDGE = 0xFFFFFFFF;
	private static final int LABEL_BACK = 0xA0000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int SUBTEXT = 0xFFB0B8C0;

	/** One slot of the ring; a null variant is "yourself". */
	public record Entry(@Nullable MorphVariant variant, Component name, @Nullable LivingEntity model, ItemStack icon) {}

	private final List<Entry> entries = new ArrayList<>();
	private final long openedAt = Util.getMillis();
	private boolean hadFavorites;
	/** The key was tapped, not held, so the ring stays open until a click or another press. */
	private boolean tapped;

	public MorphRadialScreen() {
		super(Component.literal("Favourite morphs"));
	}

	@Override
	protected void init() {
		entries.clear();
		entries.add(new Entry(null, Component.literal("Yourself"), minecraft.player, new ItemStack(Items.PLAYER_HEAD)));
		int id = MODEL_ID_BASE;
		for (MorphVariant variant : minecraft.player.getAttachedOrElse(MorphState.FAVORITES, List.<MorphVariant>of())) {
			EntityType<?> type = variant.type();
			if (entries.size() > MAX_FAVORITES) {
				break;
			}
			if (type == null || !MorphState.canMorphInto(type) || !MorphState.isUnlocked(minecraft.player, variant)) {
				continue;
			}
			String look = variant.describe(minecraft.level);
			Component name = look.isEmpty() ? type.getDescription() : type.getDescription().copy().append(" (" + look + ")");
			entries.add(new Entry(variant, name, MorphPreviews.model(type, variant, id++), MorphPreviews.icon(type)));
		}
		hadFavorites = entries.size() > 1;
	}

	public List<Entry> entries() {
		return entries;
	}

	/** Ring size: as big as fits, but clear of the hotbar below. */
	private int radius() {
		return Mth.clamp(Math.min(width / 2, height / 2 - HOTBAR_CLEARANCE) - SLOT / 2 - 4, 40, 90);
	}

	/** The middle of a slot on screen. Slot 0 (yourself) is at the top, the rest follow clockwise. */
	public ScreenPosition slotCenter(int index) {
		double angle = -Math.PI / 2 + index * Mth.TWO_PI / entries.size();
		return new ScreenPosition(width / 2 + (int) Math.round(Math.cos(angle) * radius()),
			height / 2 + (int) Math.round(Math.sin(angle) * radius()));
	}

	/** The slot the cursor points at, by direction from the middle, or -1 in the dead zone. */
	public int slotAt(double mouseX, double mouseY) {
		double dx = mouseX - width / 2.0;
		double dy = mouseY - height / 2.0;
		if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
			return -1;
		}
		double fromTop = Math.atan2(dy, dx) + Math.PI / 2;
		int index = (int) Math.round(fromTop / (Mth.TWO_PI / entries.size()));
		return Math.floorMod(index, entries.size());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int hovered = slotAt(mouseX, mouseY);
		MorphVariant current = MorphState.currentVariant(minecraft.player);
		float spin = (Util.getMillis() % (long) (SPIN_SECONDS * 1000)) / (SPIN_SECONDS * 1000) * Mth.TWO_PI;
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			ScreenPosition center = slotCenter(i);
			int x0 = center.x() - SLOT / 2;
			int y0 = center.y() - SLOT / 2;
			graphics.fill(x0, y0, x0 + SLOT, y0 + SLOT, i == hovered ? SLOT_HOVER : SLOT_BACK);
			boolean wearing = Objects.equals(entry.variant(), current);
			graphics.outline(x0, y0, SLOT, SLOT, i == hovered ? HOVER_EDGE : wearing ? SELECTED_EDGE : BORDER);
			MorphPreviews.extract(graphics, entry.model(), entry.icon(), x0 + 2, y0 + 2, x0 + SLOT - 2, y0 + SLOT - 2,
				i == hovered ? spin : MorphPreviews.ICON_YAW);
		}

		int middle = width / 2;
		int y = height / 2 - font.lineHeight / 2;
		if (hovered >= 0) {
			Component name = entries.get(hovered).name();
			int half = font.width(name) / 2 + 4;
			graphics.fill(middle - half, y - 3, middle + half, y + font.lineHeight + 2, LABEL_BACK);
			graphics.centeredText(font, name, middle, y, TEXT);
		} else if (hadFavorites) {
			graphics.centeredText(font, Component.literal("Point at a morph"), middle, y, SUBTEXT);
		} else {
			graphics.centeredText(font, Component.literal("No starred morphs yet"), middle, y - 5, SUBTEXT);
			graphics.centeredText(font, Component.literal("Star one in the sidebar with F"), middle, y + 6, SUBTEXT);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// No blur: the world stays visible around the ring.
	}

	/** Morphs into the slot under the cursor, if any, and closes. */
	private void pickAt(double mouseX, double mouseY) {
		int slot = slotAt(mouseX, mouseY);
		if (slot >= 0) {
			MorphClient.morphInto(entries.get(slot).variant());
		}
		onClose();
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (MorphClient.RADIAL.matches(event)) {
			double mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
			double mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
			// Let go after holding: pick or cancel. A tap keeps the ring open to click on.
			if (slotAt(mouseX, mouseY) >= 0 || Util.getMillis() - openedAt >= TAP_MILLIS) {
				pickAt(mouseX, mouseY);
			} else {
				tapped = true;
			}
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Pressed again after a tap: same as letting go after a hold. Before that, these are the held key repeating.
		if (MorphClient.RADIAL.matches(event)) {
			if (tapped) {
				pickAt(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()), minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		pickAt(event.x(), event.y());
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

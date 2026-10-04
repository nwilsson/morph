package com.noelwilsson.morph.client;

import com.noelwilsson.morph.MorphState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import org.jspecify.annotations.Nullable;

/**
 * A panel on the right edge of the screen, top to bottom, listing the player's unlocked morphs. Scroll with the
 * mouse wheel, click to morph. The world stays visible and keeps running behind it.
 */
public class MorphSidebarScreen extends Screen {
	public static final int PANEL_WIDTH = 110;
	public static final int HEADER_HEIGHT = 26;
	public static final int ENTRY_HEIGHT = 24;
	private static final int SCROLL_STEP = ENTRY_HEIGHT;

	private static final int PANEL = 0xD0101418;
	private static final int BORDER = 0xFF3A4450;
	private static final int HOVER = 0x40FFFFFF;
	private static final int SELECTED = 0x5040C060;
	private static final int SELECTED_EDGE = 0xFF50D070;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int SUBTEXT = 0xFFB0B8C0;
	private static final int HEART = 0xFFFF5555;

	/** One row: null type means "yourself" (unmorph). */
	private record Entry(@Nullable EntityType<?> type, Component name, ItemStack icon, String detail) {}

	private final List<Entry> entries = new ArrayList<>();
	private double scroll;

	public MorphSidebarScreen() {
		super(Component.literal("Morphs"));
	}

	@Override
	protected void init() {
		entries.clear();
		entries.add(new Entry(null, Component.literal("Yourself"), new ItemStack(Items.PLAYER_HEAD), "Unmorph"));
		List<EntityType<?>> unlocked = new ArrayList<>();
		for (Identifier id : minecraft.player.getAttachedOrElse(MorphState.UNLOCKED, List.of())) {
			BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(unlocked::add);
		}
		unlocked.sort(Comparator.comparing(type -> type.getDescription().getString()));
		for (EntityType<?> type : unlocked) {
			ItemStack icon = SpawnEggItem.byId(type).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.BARRIER));
			entries.add(new Entry(type, type.getDescription(), icon, hearts(type)));
		}
		// Open with the current morph in view.
		int current = indexOf(MorphState.current(minecraft.player));
		scroll = clampScroll(current * ENTRY_HEIGHT - (listHeight() - ENTRY_HEIGHT) / 2.0);
	}

	@SuppressWarnings("unchecked")
	private static String hearts(EntityType<?> type) {
		if (!DefaultAttributes.hasSupplier(type)) {
			return "";
		}
		double health = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type).getValue(Attributes.MAX_HEALTH);
		double hearts = health / 2.0;
		return "❤ " + (hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.valueOf(hearts));
	}

	public int panelLeft() {
		return width - PANEL_WIDTH;
	}

	private int listTop() {
		return HEADER_HEIGHT;
	}

	private int listHeight() {
		return height - listTop();
	}

	private int contentHeight() {
		return entries.size() * ENTRY_HEIGHT;
	}

	private double clampScroll(double value) {
		return Mth.clamp(value, 0, Math.max(0, contentHeight() - listHeight()));
	}

	/** Top y of an entry on screen, after scrolling. */
	public int entryTop(int index) {
		return listTop() + index * ENTRY_HEIGHT - (int) scroll;
	}

	public int indexOf(@Nullable EntityType<?> type) {
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).type() == type) {
				return i;
			}
		}
		return -1;
	}

	private int entryAt(double mouseX, double mouseY) {
		if (mouseX < panelLeft() || mouseY < listTop()) {
			return -1;
		}
		int index = (int) ((mouseY - listTop() + scroll) / ENTRY_HEIGHT);
		return index >= 0 && index < entries.size() ? index : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int left = panelLeft();
		graphics.fill(left, 0, width, height, PANEL);
		graphics.fill(left, 0, left + 1, height, BORDER);

		graphics.text(font, Component.literal("Morphs"), left + 8, 6, TEXT);
		String count = String.valueOf(entries.size() - 1);
		graphics.text(font, count, width - 8 - font.width(count), 6, SUBTEXT);
		graphics.text(font, Component.literal("Click to morph"), left + 8, 16, SUBTEXT);
		graphics.fill(left + 1, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, BORDER);

		EntityType<?> current = MorphState.current(minecraft.player);
		int hovered = entryAt(mouseX, mouseY);
		graphics.enableScissor(left + 1, listTop(), width, height);
		for (int i = 0; i < entries.size(); i++) {
			int top = entryTop(i);
			if (top + ENTRY_HEIGHT < listTop() || top > height) {
				continue;
			}
			Entry entry = entries.get(i);
			if (entry.type() == current) {
				graphics.fill(left + 1, top, width, top + ENTRY_HEIGHT, SELECTED);
				graphics.fill(left + 1, top, left + 3, top + ENTRY_HEIGHT, SELECTED_EDGE);
			} else if (i == hovered) {
				graphics.fill(left + 1, top, width, top + ENTRY_HEIGHT, HOVER);
			}
			graphics.item(entry.icon(), left + 8, top + 4);
			graphics.text(font, font.plainSubstrByWidth(entry.name().getString(), width - left - 34), left + 30, top + 3, TEXT);
			graphics.text(font, entry.detail(), left + 30, top + 13, entry.type() == null ? SUBTEXT : HEART);
		}
		graphics.disableScissor();

		if (entries.size() == 1) {
			graphics.text(font, Component.literal("Kill a mob"), left + 8, entryTop(1) + 4, SUBTEXT);
			graphics.text(font, Component.literal("to unlock it"), left + 8, entryTop(1) + 14, SUBTEXT);
		}

		// Scrollbar, only when the list overflows.
		int overflow = contentHeight() - listHeight();
		if (overflow > 0) {
			int trackHeight = listHeight();
			int thumbHeight = Math.max(16, trackHeight * listHeight() / contentHeight());
			int thumbTop = listTop() + (int) ((trackHeight - thumbHeight) * (scroll / overflow));
			graphics.fill(width - 3, listTop(), width, height, 0x40FFFFFF);
			graphics.fill(width - 3, thumbTop, width, thumbTop + thumbHeight, 0xC0FFFFFF);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// No blur or darkening: the world stays visible next to the sidebar.
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = clampScroll(scroll - scrollY * SCROLL_STEP);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int index = entryAt(event.x(), event.y());
		if (index < 0) {
			if (event.x() < panelLeft()) {
				onClose();
				return true;
			}
			return super.mouseClicked(event, doubleClick);
		}
		EntityType<?> type = entries.get(index).type();
		if (type == null) {
			minecraft.player.connection.sendCommand("unmorph");
		} else {
			minecraft.player.connection.sendCommand("morph " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
		}
		onClose();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (MorphClient.OPEN_SIDEBAR.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	public double scroll() {
		return scroll;
	}
}

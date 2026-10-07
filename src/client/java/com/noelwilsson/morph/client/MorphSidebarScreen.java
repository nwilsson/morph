package com.noelwilsson.morph.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.noelwilsson.morph.MorphFavoritePayload;
import com.noelwilsson.morph.MorphProgress;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * A panel on the right edge of the screen: a search box, a slowly turning 3D preview, then the player's unlocked
 * morphs, one row per mob, starred ones first. A mob with several looks (sheep colours, cat variants) shows one of them
 * and a "+N" badge; expanding it opens a grid of 3D tiles, one per look. Scroll with the mouse wheel, click to morph,
 * F or middle-click to star a look for the radial menu. The world keeps running behind it.
 */
public class MorphSidebarScreen extends Screen {
	public static final int PANEL_WIDTH = 140;
	public static final int HEADER_HEIGHT = 32;
	/** The search box, below the title. */
	public static final int SEARCH_TOP = 16;
	public static final int SEARCH_HEIGHT = 12;
	/** The preview takes a third of the screen, within these bounds, so short screens keep room for the list. */
	private static final int PREVIEW_MIN_HEIGHT = 60;
	private static final int PREVIEW_MAX_HEIGHT = 100;
	public static final int ENTRY_HEIGHT = 24;
	public static final int TILE = 20;
	/** Width of the expand arrow at the right of a row with several looks. */
	public static final int ARROW_WIDTH = 16;
	private static final int GRID_LEFT = 8;
	private static final int GRID_BOTTOM_PAD = 4;
	private static final int SCROLL_STEP = ENTRY_HEIGHT;

	/** Seconds for one full turn of the preview. */
	private static final float SPIN_SECONDS = 12;
	/** Entity IDs for the models, far from real (positive) IDs and from disguises (just below zero). */
	private static final int MODEL_ID_BASE = Integer.MIN_VALUE / 2;

	private static final int PANEL = 0xD0101418;
	private static final int BORDER = 0xFF3A4450;
	private static final int PREVIEW_BACK = 0x30000000;
	private static final int SEARCH_BACK = 0x60000000;
	private static final int SEARCH_FOCUSED = 0xFF6A7480;
	private static final int HOVER = 0x40FFFFFF;
	private static final int TILE_BACK = 0x18FFFFFF;
	private static final int SELECTED = 0x5040C060;
	private static final int SELECTED_EDGE = 0xFF50D070;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int SUBTEXT = 0xFFB0B8C0;
	private static final int HEART = 0xFFFF5555;
	private static final int STAR = 0xFFFFD040;
	private static final String STAR_MARK = "★";

	/** One look of a mob; a null variant is "yourself". The model is null when the mob can't be made, then the icon shows. */
	private record Look(@Nullable MorphVariant variant, String description, @Nullable LivingEntity model, ItemStack icon) {}

	/** Every unlocked look of one mob, the default look first. A null type is "yourself". */
	private record Group(@Nullable EntityType<?> type, Component name, String hearts, List<Look> looks) {
		boolean hasVariants() {
			return looks.size() > 1;
		}
	}

	/** What the mouse is over: a group's row (and maybe its arrow), or one tile of its grid. */
	private record Hit(Group group, @Nullable Look tile, boolean arrow) {}

	/** Every group, yourself first, then starred mobs, then the rest by name. */
	private final List<Group> all = new ArrayList<>();
	/** The groups the search lets through, in the same order. */
	private final List<Group> groups = new ArrayList<>();
	private @Nullable Group expanded;
	private double scroll;
	private @Nullable EditBox search;
	/** "37/84": mobs collected out of every mob with a spawn egg (MorphProgress). */
	private String progress = "";

	public MorphSidebarScreen() {
		super(Component.literal("Morphs"));
	}

	@Override
	protected void init() {
		all.clear();
		all.add(new Group(null, Component.literal("Yourself"), "Unmorph",
			List.of(new Look(null, "", minecraft.player, new ItemStack(Items.PLAYER_HEAD)))));
		Map<EntityType<?>, List<Look>> byType = new LinkedHashMap<>();
		int id = MODEL_ID_BASE;
		for (MorphVariant variant : minecraft.player.getAttachedOrElse(MorphState.UNLOCKED, List.<MorphVariant>of())) {
			EntityType<?> type = variant.type();
			// A server can block a mob after it was unlocked (#morph:blocked); it can't be picked, so it isn't listed.
			if (type != null && MorphState.canMorphInto(type)) {
				byType.computeIfAbsent(type, t -> new ArrayList<>())
					.add(new Look(variant, variant.describe(minecraft.level), MorphPreviews.model(type, variant, id++), MorphPreviews.icon(type)));
			}
		}
		List<Group> mobs = new ArrayList<>();
		byType.forEach((type, looks) -> {
			looks.sort(Comparator.comparing((Look look) -> !look.variant().isDefault()).thenComparing(Look::description));
			mobs.add(new Group(type, type.getDescription(), hearts(type), looks));
		});
		// Sorted once: starring a mob while the sidebar is open marks it but doesn't move rows under the mouse.
		mobs.sort(Comparator.comparing((Group group) -> !isStarred(group)).thenComparing(group -> group.name().getString()));
		all.addAll(mobs);
		Set<EntityType<?>> collectable = MorphProgress.collectable();
		progress = MorphProgress.collected(minecraft.player, collectable) + "/" + collectable.size();

		int searchLeft = panelLeft() + 9;
		search = new EditBox(font, searchLeft, SEARCH_TOP + 2, width - 9 - searchLeft, font.lineHeight, search, Component.literal("Search"));
		search.setBordered(false);
		search.setMaxLength(50);
		search.setHint(Component.literal("Search · F to star").withStyle(EditBox.SEARCH_HINT_STYLE));
		search.setResponder(text -> filter());
		addWidget(search);
		filter();

		// Open with the current morph's looks spread out and in view.
		Group current = groupOf(MorphState.currentVariant(minecraft.player));
		expanded = current != null && current.hasVariants() ? current : null;
		int top = current == null ? 0 : offsetOf(current);
		scroll = clampScroll(top - (listHeight() - groupHeight(current == null ? groups.getFirst() : current)) / 2.0);
	}

	/** Shows only the mobs whose name, or the description of one of their looks, holds the search text. */
	private void filter() {
		String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		groups.clear();
		for (Group group : all) {
			if (query.isEmpty() || matches(group, query)) {
				groups.add(group);
			}
		}
		if (expanded != null && !groups.contains(expanded)) {
			expanded = null;
		}
		scroll = clampScroll(query.isEmpty() ? scroll : 0);
	}

	private static boolean matches(Group group, String query) {
		if (group.name().getString().toLowerCase(Locale.ROOT).contains(query)) {
			return true;
		}
		for (Look look : group.looks()) {
			if (look.description().toLowerCase(Locale.ROOT).contains(query)) {
				return true;
			}
		}
		return false;
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

	private boolean isStarred(Look look) {
		return look.variant() != null && MorphState.isFavorite(minecraft.player, look.variant());
	}

	private boolean isStarred(Group group) {
		for (Look look : group.looks()) {
			if (isStarred(look)) {
				return true;
			}
		}
		return false;
	}

	public int panelLeft() {
		return width - PANEL_WIDTH;
	}

	private int previewHeight() {
		return Mth.clamp(height / 3, PREVIEW_MIN_HEIGHT, PREVIEW_MAX_HEIGHT);
	}

	public int listTop() {
		return HEADER_HEIGHT + previewHeight();
	}

	private int listHeight() {
		return height - listTop();
	}

	private int columns() {
		return (PANEL_WIDTH - 2 * GRID_LEFT) / TILE;
	}

	private int gridHeight(Group group) {
		return Mth.positiveCeilDiv(group.looks().size(), columns()) * TILE + GRID_BOTTOM_PAD;
	}

	private int groupHeight(Group group) {
		return ENTRY_HEIGHT + (group == expanded ? gridHeight(group) : 0);
	}

	/** Where a group's row starts in the list, before scrolling. */
	private int offsetOf(Group group) {
		int offset = 0;
		for (Group other : groups) {
			if (other == group) {
				break;
			}
			offset += groupHeight(other);
		}
		return offset;
	}

	private int contentHeight() {
		int height = 0;
		for (Group group : groups) {
			height += groupHeight(group);
		}
		return height;
	}

	private double clampScroll(double value) {
		return Mth.clamp(value, 0, Math.max(0, contentHeight() - listHeight()));
	}

	/** Scrolls just enough to show the whole group, or its top if it's taller than the list. */
	private void scrollTo(Group group) {
		int top = offsetOf(group);
		int bottom = top + groupHeight(group);
		if (bottom - scroll > listHeight()) {
			scroll = bottom - listHeight();
		}
		if (top < scroll) {
			scroll = top;
		}
		scroll = clampScroll(scroll);
	}

	private @Nullable Group groupOf(@Nullable MorphVariant variant) {
		for (Group group : groups) {
			for (Look look : group.looks()) {
				if (Objects.equals(look.variant(), variant)) {
					return group;
				}
			}
		}
		return null;
	}

	private @Nullable Group groupOf(EntityType<?> type) {
		for (Group group : groups) {
			if (group.type() == type) {
				return group;
			}
		}
		return null;
	}

	/** The look a collapsed row shows: the one you're wearing if it's this mob, otherwise the default. */
	private Look shown(Group group) {
		MorphVariant current = MorphState.currentVariant(minecraft.player);
		for (Look look : group.looks()) {
			if (Objects.equals(look.variant(), current)) {
				return look;
			}
		}
		return group.looks().getFirst();
	}

	/** Top y of a mob's row on screen, after scrolling, or -1 if it isn't listed. Null type is "yourself". */
	public int rowTop(@Nullable EntityType<?> type) {
		Group group = type == null ? all.getFirst() : groupOf(type);
		return group == null || !groups.contains(group) ? -1 : listTop() + offsetOf(group) - (int) scroll;
	}

	public boolean isExpanded(EntityType<?> type) {
		return expanded != null && expanded.type() == type;
	}

	/** The mobs the search lets through, in list order. Null is "yourself". */
	public List<@Nullable EntityType<?>> listed() {
		return groups.stream().<@Nullable EntityType<?>>map(Group::type).toList();
	}

	/** The middle of a look's tile on screen, or null if its mob isn't expanded. */
	public @Nullable ScreenPosition tileCenter(MorphVariant variant) {
		Group group = groupOf(variant);
		if (group == null || group != expanded) {
			return null;
		}
		int index = 0;
		while (!Objects.equals(group.looks().get(index).variant(), variant)) {
			index++;
		}
		int columns = columns();
		return new ScreenPosition(panelLeft() + GRID_LEFT + index % columns * TILE + TILE / 2,
			rowTop(group.type()) + ENTRY_HEIGHT + index / columns * TILE + TILE / 2);
	}

	private @Nullable Hit hitAt(double mouseX, double mouseY) {
		if (mouseX < panelLeft() || mouseY < listTop()) {
			return null;
		}
		double y = mouseY - listTop() + scroll;
		int top = 0;
		for (Group group : groups) {
			int height = groupHeight(group);
			if (y < top + height) {
				if (y < top + ENTRY_HEIGHT) {
					return new Hit(group, null, group.hasVariants() && mouseX >= width - ARROW_WIDTH - 2);
				}
				double gridX = mouseX - panelLeft() - GRID_LEFT;
				int column = (int) (gridX / TILE);
				int index = (int) ((y - top - ENTRY_HEIGHT) / TILE) * columns() + column;
				if (gridX >= 0 && column < columns() && index < group.looks().size()) {
					return new Hit(group, group.looks().get(index), false);
				}
				return null;
			}
			top += height;
		}
		return null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int left = panelLeft();
		graphics.fill(left, 0, width, height, PANEL);
		graphics.fill(left, 0, left + 1, height, BORDER);

		graphics.text(font, Component.literal("Morphs"), left + 8, 5, TEXT);
		graphics.text(font, progress, width - 8 - font.width(progress), 5, SUBTEXT);
		int searchBottom = SEARCH_TOP + SEARCH_HEIGHT;
		graphics.fill(left + 6, SEARCH_TOP, width - 6, searchBottom, SEARCH_BACK);
		if (search.isFocused()) {
			graphics.outline(left + 6, SEARCH_TOP, PANEL_WIDTH - 12, SEARCH_HEIGHT, SEARCH_FOCUSED);
		}
		search.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.fill(left + 1, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, BORDER);

		MorphVariant current = MorphState.currentVariant(minecraft.player);
		Hit hovered = hitAt(mouseX, mouseY);
		extractPreview(graphics, hovered, current);

		graphics.enableScissor(left + 1, listTop(), width, height);
		for (Group group : groups) {
			int top = rowTop(group.type());
			if (top + groupHeight(group) < listTop() || top > height) {
				continue;
			}
			extractRow(graphics, group, top, hovered, current);
			if (group == expanded) {
				extractGrid(graphics, group, top + ENTRY_HEIGHT, hovered, current);
			}
		}
		graphics.disableScissor();

		if (all.size() == 1 && groups.size() == 1) {
			int top = rowTop(null) + ENTRY_HEIGHT;
			graphics.text(font, Component.literal("Kill a mob"), left + 8, top + 4, SUBTEXT);
			graphics.text(font, Component.literal("to unlock it"), left + 8, top + 14, SUBTEXT);
		} else if (groups.isEmpty()) {
			graphics.text(font, Component.literal("No morph matches"), left + 8, listTop() + 6, SUBTEXT);
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

	/** The big model on top: whatever the mouse is over, otherwise what you are now. */
	private void extractPreview(GuiGraphicsExtractor graphics, @Nullable Hit hovered, @Nullable MorphVariant current) {
		Group group;
		Look look;
		if (hovered != null) {
			group = hovered.group();
			look = hovered.tile() != null ? hovered.tile() : shown(group);
		} else {
			group = Objects.requireNonNullElse(groupOf(current), all.getFirst());
			look = shown(group);
		}
		int left = panelLeft();
		int top = HEADER_HEIGHT;
		int bottom = top + previewHeight();
		graphics.fill(left + 1, top, width, bottom, PREVIEW_BACK);
		float spin = (Util.getMillis() % (long) (SPIN_SECONDS * 1000)) / (SPIN_SECONDS * 1000) * Mth.TWO_PI;
		MorphPreviews.extract(graphics, look.model(), look.icon(), left + 10, top + 4, width - 10, bottom - 26, spin);
		if (isStarred(look)) {
			graphics.text(font, STAR_MARK, width - 6 - font.width(STAR_MARK), top + 4, STAR);
		}

		int middle = left + PANEL_WIDTH / 2;
		graphics.centeredText(font, font.plainSubstrByWidth(group.name().getString(), PANEL_WIDTH - 8), middle, bottom - 23, TEXT);
		String detail = look.description();
		if (detail.isEmpty()) {
			graphics.centeredText(font, group.hearts(), middle, bottom - 12, group.type() == null ? SUBTEXT : HEART);
		} else {
			String hearts = group.hearts() + " ";
			detail = font.plainSubstrByWidth(detail, PANEL_WIDTH - 8 - font.width(hearts));
			int x = middle - (font.width(hearts) + font.width(detail)) / 2;
			graphics.text(font, hearts, x, bottom - 12, HEART);
			graphics.text(font, detail, x + font.width(hearts), bottom - 12, SUBTEXT);
		}
		graphics.fill(left + 1, bottom - 1, width, bottom, BORDER);
	}

	private void extractRow(GuiGraphicsExtractor graphics, Group group, int top, @Nullable Hit hovered, @Nullable MorphVariant current) {
		int left = panelLeft();
		Look look = shown(group);
		boolean wearing = Objects.equals(look.variant(), current);
		if (wearing) {
			graphics.fill(left + 1, top, width, top + ENTRY_HEIGHT, SELECTED);
			graphics.fill(left + 1, top, left + 3, top + ENTRY_HEIGHT, SELECTED_EDGE);
		} else if (hovered != null && hovered.group() == group && hovered.tile() == null) {
			graphics.fill(left + 1, top, width, top + ENTRY_HEIGHT, HOVER);
		}
		MorphPreviews.extract(graphics, look.model(), look.icon(), left + 6, top + 2, left + 26, top + 22, MorphPreviews.ICON_YAW);

		int right = width - 6;
		if (group.hasVariants()) {
			boolean onArrow = hovered != null && hovered.group() == group && hovered.arrow();
			String arrow = group == expanded ? "▼" : "▶";
			graphics.text(font, arrow, width - ARROW_WIDTH / 2 - 2 - font.width(arrow) / 2, top + 8, onArrow ? TEXT : SUBTEXT);
			String badge = "+" + (group.looks().size() - 1);
			right = width - ARROW_WIDTH - 4;
			graphics.text(font, badge, right - font.width(badge), top + 8, SUBTEXT);
			right -= font.width(badge) + 4;
		}
		if (isStarred(group)) {
			graphics.text(font, STAR_MARK, right - font.width(STAR_MARK), top + 8, STAR);
			right -= font.width(STAR_MARK) + 3;
		}
		graphics.text(font, font.plainSubstrByWidth(group.name().getString(), right - left - 30), left + 30, top + 3, TEXT);
		graphics.text(font, group.hearts(), left + 30, top + 13, group.type() == null ? SUBTEXT : HEART);
	}

	private void extractGrid(GuiGraphicsExtractor graphics, Group group, int top, @Nullable Hit hovered, @Nullable MorphVariant current) {
		int columns = columns();
		for (int i = 0; i < group.looks().size(); i++) {
			Look look = group.looks().get(i);
			int x = panelLeft() + GRID_LEFT + i % columns * TILE;
			int y = top + i / columns * TILE;
			boolean isHovered = hovered != null && hovered.tile() == look;
			graphics.fill(x + 1, y + 1, x + TILE - 1, y + TILE - 1, isHovered ? HOVER : TILE_BACK);
			if (Objects.equals(look.variant(), current)) {
				graphics.outline(x, y, TILE, TILE, SELECTED_EDGE);
			}
			MorphPreviews.extract(graphics, look.model(), look.icon(), x + 1, y + 1, x + TILE - 1, y + TILE - 1, MorphPreviews.ICON_YAW);
			if (isStarred(look)) {
				graphics.text(font, STAR_MARK, x + TILE - 1 - font.width(STAR_MARK), y + 1, STAR);
			}
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
		Hit hit = hitAt(event.x(), event.y());
		if (hit == null) {
			if (event.x() < panelLeft()) {
				onClose();
				return true;
			}
			// The search box, or empty panel space, which takes the focus off the search box.
			if (!super.mouseClicked(event, doubleClick)) {
				clearFocus();
			}
			return true;
		}
		// The arrow, or a right click anywhere on the row, opens or closes the mob's looks.
		if (hit.tile() == null && hit.group().hasVariants() && (hit.arrow() || event.button() == InputConstants.MOUSE_BUTTON_RIGHT)) {
			expanded = expanded == hit.group() ? null : hit.group();
			scroll = clampScroll(scroll);
			if (expanded != null) {
				scrollTo(expanded);
			}
			return true;
		}
		Look look = hit.tile() != null ? hit.tile() : shown(hit.group());
		if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
			star(look);
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		pick(look);
		return true;
	}

	/** Morphs into the look and closes the sidebar. */
	private void pick(Look look) {
		MorphClient.morphInto(look.variant());
		onClose();
	}

	/** Stars or unstars a look. The server keeps the list; the star shows once it syncs back. */
	private void star(Look look) {
		if (look.variant() != null && ClientPlayNetworking.canSend(MorphFavoritePayload.TYPE)) {
			ClientPlayNetworking.send(new MorphFavoritePayload(look.variant()));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (search.isFocused()) {
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				// Enter picks the first match: open, type "cre", Enter, and you're a creeper.
				Group first = groups.stream().filter(group -> group.type() != null).findFirst().orElse(null);
				if (first != null && !search.getValue().isBlank()) {
					pick(shown(first));
				}
				return true;
			}
			if (event.isEscape() && !search.getValue().isEmpty()) {
				search.setValue("");
				return true;
			}
			return super.keyPressed(event);
		}
		if (MorphClient.OPEN_SIDEBAR.matches(event)) {
			onClose();
			return true;
		}
		if (event.key() == InputConstants.KEY_F) {
			Hit hit = hitAt(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()), minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
			if (hit != null) {
				star(hit.tile() != null ? hit.tile() : shown(hit.group()));
			}
			return true;
		}
		return super.keyPressed(event);
	}

	/** Focuses the search box, as clicking it would. */
	public void focusSearch() {
		setFocused(search);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	public double scroll() {
		return scroll;
	}
}

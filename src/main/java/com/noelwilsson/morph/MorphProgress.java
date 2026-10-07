package com.noelwilsson.morph;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import org.jspecify.annotations.Nullable;

/**
 * How many mobs a player has collected, and the Morphed advancement tab. A mob counts toward the total if it can be
 * a morph and has a spawn egg: every mob you can meet in survival, without the giant and the illusioner, which never
 * spawn. Modded mobs with eggs count too. Each look of a mob is the same mob here.
 */
public final class MorphProgress {
	/** Advancement and the number of mobs that earns it; 0 means every mob. The criterion is always "unlocked". */
	private record Milestone(Identifier advancement, int mobs) {}

	private static final List<Milestone> MILESTONES = List.of(
		new Milestone(Morph.id("root"), 1),
		new Milestone(Morph.id("ten_morphs"), 10),
		new Milestone(Morph.id("fifty_morphs"), 50),
		new Milestone(Morph.id("all_morphs"), 0));
	private static final String CRITERION = "unlocked";
	/** Mobs with a spawn egg. Items are fixed once the game has loaded, so this is worked out once. */
	private static @Nullable Set<EntityType<?>> withEggs;

	private MorphProgress() {}

	/** Mobs that count toward collecting them all. Tags can change on /reload, so this is asked each time. */
	public static Set<EntityType<?>> collectable() {
		if (withEggs == null) {
			Set<EntityType<?>> eggs = new HashSet<>();
			for (Item item : BuiltInRegistries.ITEM) {
				EntityType<?> type = SpawnEggItem.getType(item.getDefaultInstance());
				if (type != null) {
					eggs.add(type);
				}
			}
			withEggs = Set.copyOf(eggs);
		}
		Set<EntityType<?>> types = new HashSet<>();
		for (EntityType<?> type : withEggs) {
			if (MorphState.canMorphInto(type)) {
				types.add(type);
			}
		}
		return types;
	}

	/** Collectable mobs this player has unlocked any look of. */
	public static int collected(Player player, Set<EntityType<?>> collectable) {
		Set<EntityType<?>> unlocked = new HashSet<>();
		for (MorphVariant variant : player.getAttachedOrElse(MorphState.UNLOCKED, List.<MorphVariant>of())) {
			EntityType<?> type = variant.type();
			if (type != null && collectable.contains(type)) {
				unlocked.add(type);
			}
		}
		return unlocked.size();
	}

	/** Awards the milestones this player has reached. Called on every unlock and when they join (for older saves). */
	public static void update(ServerPlayer player) {
		Set<EntityType<?>> collectable = collectable();
		int collected = collected(player, collectable);
		for (Milestone milestone : MILESTONES) {
			int needed = milestone.mobs() == 0 ? collectable.size() : milestone.mobs();
			AdvancementHolder advancement = player.level().getServer().getAdvancements().get(milestone.advancement());
			if (needed > 0 && collected >= needed && advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone()) {
				player.getAdvancements().award(advancement, CRITERION);
			}
		}
	}
}

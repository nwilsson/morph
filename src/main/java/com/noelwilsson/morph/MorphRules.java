package com.noelwilsson.morph;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * What a server can change: gamerules (/gamerule morph:...) and two entity type tags a datapack can fill in. Mobs in
 * #morph:blocked can't be unlocked or morphed into; mobs in #morph:powerless keep their body but lose their power.
 * A datapack can also replace the morph:flight advancement to change what earns flight.
 */
public final class MorphRules {
	public static final GameRuleCategory CATEGORY = GameRuleCategory.register(Morph.id("morph"));

	/** Kills of a mob needed before it unlocks. After the first unlock, other looks of that mob unlock on one kill. */
	public static final GameRule<Integer> KILLS_TO_UNLOCK = GameRuleBuilder.forInteger(1).minValue(1).category(CATEGORY)
		.buildAndRegister(Morph.id("kills_to_unlock"));
	/** Off: dying forgets every unlocked morph, like Identity's and Woodwalkers' hardcore options. */
	public static final GameRule<Boolean> KEEP_MORPHS_ON_DEATH = GameRuleBuilder.forBoolean(true).category(CATEGORY)
		.buildAndRegister(Morph.id("keep_morphs_on_death"));
	/** On: unlocking a new morph turns you into it straight away. */
	public static final GameRule<Boolean> MORPH_ON_UNLOCK = GameRuleBuilder.forBoolean(false).category(CATEGORY)
		.buildAndRegister(Morph.id("morph_on_unlock"));
	/** Off: flying mobs only flutter down (slow falling) instead of flying. */
	public static final GameRule<Boolean> ALLOW_FLIGHT = GameRuleBuilder.forBoolean(true).category(CATEGORY)
		.buildAndRegister(Morph.id("allow_flight"));
	/** On: flying mobs only fly once you have the morph:flight advancement (by default: you've been to the End). */
	public static final GameRule<Boolean> FLIGHT_NEEDS_ADVANCEMENT = GameRuleBuilder.forBoolean(false).category(CATEGORY)
		.buildAndRegister(Morph.id("flight_needs_advancement"));
	public static final GameRule<MonsterBehavior> MONSTER_BEHAVIOR = GameRuleBuilder.forEnum(MonsterBehavior.DISGUISE).category(CATEGORY)
		.buildAndRegister(Morph.id("monster_behavior"));

	public static final TagKey<EntityType<?>> BLOCKED = TagKey.create(Registries.ENTITY_TYPE, Morph.id("blocked"));
	public static final TagKey<EntityType<?>> POWERLESS = TagKey.create(Registries.ENTITY_TYPE, Morph.id("powerless"));
	/** Hidden advancement that earns flight while morph:flight_needs_advancement is on. */
	public static final Identifier FLIGHT_ADVANCEMENT = Morph.id("flight");

	/** How monsters treat a morphed player. Constant names are what /gamerule takes. */
	public enum MonsterBehavior {
		/** Like the mob you look like: zombies leave a cow alone but still hunt a villager. Golems hunt monster morphs. */
		DISGUISE,
		/** Monsters leave you alone only while you're a monster yourself; as a cow you're fair game. */
		MONSTERS_ONLY,
		/** The disguise only works from afar: monsters that DISGUISE would fool notice you from half as far, then attack. */
		SHORT_RANGE,
		/** Morphing doesn't change how any mob treats you. */
		OFF
	}

	private MorphRules() {}

	public static void init() {
		// Class loading registers the gamerules.
	}

	public static boolean isBlocked(EntityType<?> type) {
		return type.builtInRegistryHolder().is(BLOCKED);
	}

	public static boolean isPowerless(EntityType<?> type) {
		return type.builtInRegistryHolder().is(POWERLESS);
	}

	public static int killsToUnlock(ServerLevel level) {
		return level.getGameRules().get(KILLS_TO_UNLOCK);
	}

	public static boolean keepMorphsOnDeath(ServerLevel level) {
		return level.getGameRules().get(KEEP_MORPHS_ON_DEATH);
	}

	public static boolean morphOnUnlock(ServerLevel level) {
		return level.getGameRules().get(MORPH_ON_UNLOCK);
	}

	public static boolean allowFlight(ServerLevel level) {
		return level.getGameRules().get(ALLOW_FLIGHT);
	}

	/**
	 * Whether a flying morph may actually fly: morph:allow_flight is on and, if morph:flight_needs_advancement is too,
	 * the player has the morph:flight advancement. A datapack that removes the advancement removes the requirement.
	 */
	public static boolean mayFly(ServerPlayer player) {
		ServerLevel level = player.level();
		if (!allowFlight(level)) {
			return false;
		}
		if (!level.getGameRules().get(FLIGHT_NEEDS_ADVANCEMENT)) {
			return true;
		}
		AdvancementHolder flight = level.getServer().getAdvancements().get(FLIGHT_ADVANCEMENT);
		return flight == null || player.getAdvancements().getOrStartProgress(flight).isDone();
	}

	public static MonsterBehavior monsterBehavior(ServerLevel level) {
		return level.getGameRules().get(MONSTER_BEHAVIOR);
	}
}

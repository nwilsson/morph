package com.noelwilsson.morph;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** What each mob's body lets the player do. Health and hitbox come from the mob itself; this is the rest. */
public final class MorphAbilities {
	public enum Ability {
		FLY,             // creative-style flight, no fall damage
		SLOW_FALL,       // flaps instead of falling
		WATER_BREATHING,
		SWIM,            // dolphin's grace
		FIRE_IMMUNE,
		CLIMB,           // walls act like ladders
		NIGHT_VISION,
		JUMP,
		SPEED
	}

	private static final Map<String, Set<Ability>> TABLE = Map.ofEntries(
		e("parrot", Ability.FLY),
		e("bat", Ability.FLY, Ability.NIGHT_VISION),
		e("bee", Ability.FLY),
		e("allay", Ability.FLY),
		e("vex", Ability.FLY),
		e("phantom", Ability.FLY, Ability.NIGHT_VISION),
		e("ghast", Ability.FLY),
		e("happy_ghast", Ability.FLY),
		e("blaze", Ability.FLY),
		e("breeze", Ability.JUMP),
		e("wither", Ability.FLY),
		e("ender_dragon", Ability.FLY),
		e("chicken", Ability.SLOW_FALL),
		e("spider", Ability.CLIMB, Ability.NIGHT_VISION),
		e("cave_spider", Ability.CLIMB, Ability.NIGHT_VISION),
		e("cod", Ability.WATER_BREATHING, Ability.SWIM),
		e("salmon", Ability.WATER_BREATHING, Ability.SWIM),
		e("tropical_fish", Ability.WATER_BREATHING, Ability.SWIM),
		e("pufferfish", Ability.WATER_BREATHING, Ability.SWIM),
		e("squid", Ability.WATER_BREATHING, Ability.SWIM),
		e("glow_squid", Ability.WATER_BREATHING, Ability.SWIM, Ability.NIGHT_VISION),
		e("dolphin", Ability.WATER_BREATHING, Ability.SWIM),
		e("axolotl", Ability.WATER_BREATHING, Ability.SWIM),
		e("guardian", Ability.WATER_BREATHING, Ability.SWIM),
		e("elder_guardian", Ability.WATER_BREATHING, Ability.SWIM),
		e("drowned", Ability.WATER_BREATHING),
		e("turtle", Ability.WATER_BREATHING),
		e("frog", Ability.WATER_BREATHING, Ability.JUMP),
		e("rabbit", Ability.JUMP, Ability.SPEED),
		e("goat", Ability.JUMP),
		e("cat", Ability.SPEED, Ability.NIGHT_VISION),
		e("ocelot", Ability.SPEED, Ability.NIGHT_VISION),
		e("fox", Ability.SPEED, Ability.NIGHT_VISION),
		e("wolf", Ability.SPEED),
		e("horse", Ability.SPEED, Ability.JUMP),
		e("camel", Ability.SPEED),
		e("enderman", Ability.SPEED)
	);

	private MorphAbilities() {}

	private static Map.Entry<String, Set<Ability>> e(String path, Ability... abilities) {
		return Map.entry(path, Set.of(abilities));
	}

	public static Set<Ability> of(EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		EnumSet<Ability> set = EnumSet.noneOf(Ability.class);
		if ("minecraft".equals(id.getNamespace())) {
			set.addAll(TABLE.getOrDefault(id.getPath(), Set.of()));
		}
		if (type.fireImmune()) {
			set.add(Ability.FIRE_IMMUNE);
		}
		return set;
	}
}

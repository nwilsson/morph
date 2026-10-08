package com.noelwilsson.morph;

import com.mojang.serialization.Codec;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * What each mob's body lets the player do. Health and hitbox come from the mob itself; this is the rest. Which mob
 * has which comes from its data file (see {@link MorphMobs}), plus fire immunity for any mob that is fire immune.
 */
public final class MorphAbilities {
	public enum Ability implements StringRepresentable {
		FLY,             // creative-style flight, no fall damage
		GLIDE,           // elytra without an elytra: jump in the air to glide, jump again to flap
		SLOW_FALL,       // flaps instead of falling
		WATER_BREATHING,
		SWIM,            // dolphin's grace
		FIRE_IMMUNE,
		CLIMB,           // walls act like ladders
		NIGHT_VISION,
		JUMP,
		SPEED,
		LAVA_WALK,       // strider
		DRIES_OUT;       // fish: suffocates out of water

		/** As written in data files: "fly", "night_vision". */
		public static final Codec<Ability> CODEC = StringRepresentable.fromEnum(Ability::values);

		@Override
		public String getSerializedName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private MorphAbilities() {}

	/** A fresh set the caller may change. Works on both sides: clients get the table from the server. */
	public static Set<Ability> of(EntityType<?> type, Level level) {
		EnumSet<Ability> set = EnumSet.noneOf(Ability.class);
		MorphMobs.Synced mob = MorphMobs.synced(type, level);
		if (mob != null) {
			set.addAll(mob.abilities());
		}
		if (type.fireImmune()) {
			set.add(Ability.FIRE_IMMUNE);
		}
		return set;
	}
}

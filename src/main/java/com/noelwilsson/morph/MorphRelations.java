package com.noelwilsson.morph;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/** How other mobs see a morphed player. */
public final class MorphRelations {
	private MorphRelations() {}

	/** The player is morphed into a monster (anything vanilla marks as Enemy: zombie, blaze, enderman, ...). */
	public static boolean looksLikeMonster(LivingEntity entity) {
		if (!(entity instanceof Player player)) {
			return false;
		}
		EntityType<?> type = MorphState.current(player);
		return type != null && MorphTemplates.get(type, player.level()) instanceof Enemy;
	}

	/** Monsters leave a monster-morphed player alone, unless that player hit them. Then they fight back. */
	public static boolean monsterIgnores(LivingEntity attacker, LivingEntity target) {
		return attacker instanceof Enemy && looksLikeMonster(target) && attacker.getLastHurtByMob() != target;
	}

	/** Village iron golems go after monster-morphed players, except creepers, like they do with real ones. */
	public static boolean golemHunts(LivingEntity target) {
		return looksLikeMonster(target) && MorphState.current((Player) target) != EntityTypes.CREEPER;
	}

	/** Creepers run from cats and ocelots, and from players who are one. */
	public static boolean scaresCreepers(LivingEntity entity) {
		if (!(entity instanceof Player player)) {
			return false;
		}
		EntityType<?> type = MorphState.current(player);
		return type == EntityTypes.CAT || type == EntityTypes.OCELOT;
	}
}

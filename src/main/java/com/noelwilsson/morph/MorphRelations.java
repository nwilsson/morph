package com.noelwilsson.morph;

import com.noelwilsson.morph.mixin.MobAccessor;
import com.noelwilsson.morph.mixin.NearestAttackableTargetGoalAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
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

	/** The morph:monster_behavior gamerule. Clients don't have gamerules, and only the server's AI matters. */
	private static MorphRules.MonsterBehavior behavior(Entity entity) {
		return entity.level() instanceof ServerLevel level ? MorphRules.monsterBehavior(level) : MorphRules.MonsterBehavior.DISGUISE;
	}

	/**
	 * Monsters treat a morphed player like the mob they look like: a zombie leaves a cow or another zombie alone but
	 * still goes after a villager or iron golem. Whoever hits a monster gets fought back, whatever they look like.
	 * With monster_behavior MONSTERS_ONLY, any monster body is left alone and any other is hunted; OFF changes nothing.
	 */
	public static boolean monsterIgnores(LivingEntity attacker, LivingEntity target) {
		if (!(attacker instanceof Enemy) || !(target instanceof Player player) || attacker.getLastHurtByMob() == target) {
			return false;
		}
		MorphRules.MonsterBehavior behavior = behavior(attacker);
		if (behavior == MorphRules.MonsterBehavior.OFF) {
			return false;
		}
		// Fleeing goes through canAttack too: a creeper that "can't attack" a cat can't see it to run from it.
		if (attacker instanceof Creeper && scaresCreepers(player)) {
			return false;
		}
		if (behavior == MorphRules.MonsterBehavior.MONSTERS_ONLY) {
			return looksLikeMonster(player);
		}
		EntityType<?> type = MorphState.current(player);
		LivingEntity body = type == null ? null : MorphTemplates.get(type, player.level());
		return body != null && !hunts(attacker, body);
	}

	/**
	 * Whether the monster goes after this kind of mob by itself: it has a target goal for the mob's class (zombies:
	 * villagers, iron golems, baby turtles). Goals aimed at players don't count, they are what the disguise hides from.
	 * Brain-driven monsters have no goals; of those, wardens and zoglins attack anything that moves.
	 */
	private static boolean hunts(LivingEntity attacker, LivingEntity body) {
		if (attacker.getType() == EntityTypes.WARDEN || attacker.getType() == EntityTypes.ZOGLIN) {
			return body.getType() != EntityTypes.CREEPER && body.getType() != attacker.getType();
		}
		if (attacker instanceof Mob mob) {
			for (WrappedGoal goal : ((MobAccessor) mob).morph$targetSelector().getAvailableGoals()) {
				if (goal.getGoal() instanceof NearestAttackableTargetGoal<?> hunt) {
					Class<?> prey = ((NearestAttackableTargetGoalAccessor) hunt).morph$targetType();
					if (prey != Player.class && prey.isInstance(body)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Village iron golems go after monster-morphed players, except creepers, like they do with real ones. */
	public static boolean golemHunts(LivingEntity target) {
		return looksLikeMonster(target) && MorphState.current((Player) target) != EntityTypes.CREEPER
			&& behavior(target) != MorphRules.MonsterBehavior.OFF;
	}

	/** Creepers run from cats and ocelots, and from players who are one. */
	public static boolean scaresCreepers(LivingEntity entity) {
		if (!(entity instanceof Player player) || behavior(player) == MorphRules.MonsterBehavior.OFF) {
			return false;
		}
		EntityType<?> type = MorphState.current(player);
		return type == EntityTypes.CAT || type == EntityTypes.OCELOT;
	}
}

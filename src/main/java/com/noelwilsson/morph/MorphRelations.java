package com.noelwilsson.morph;

import com.noelwilsson.morph.mixin.MobAccessor;
import com.noelwilsson.morph.mixin.NearestAttackableTargetGoalAccessor;
import com.noelwilsson.morph.mixin.VillagerHostilesSensorAccessor;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
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
import org.jspecify.annotations.Nullable;

/** How other mobs see a morphed player. */
public final class MorphRelations {
	/** How far, as a fraction of its usual range, a monster notices a disguised player with monster_behavior SHORT_RANGE. */
	private static final double SHORT_RANGE_VISIBILITY = 0.5;

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
	 * With monster_behavior MONSTERS_ONLY, any monster body is left alone and any other is hunted; SHORT_RANGE and OFF
	 * never make a monster leave you alone.
	 */
	public static boolean monsterIgnores(LivingEntity attacker, LivingEntity target) {
		return switch (behavior(attacker)) {
			case DISGUISE -> fooled(attacker, target);
			case MONSTERS_ONLY -> disguiseApplies(attacker, target) && looksLikeMonster(target);
			case SHORT_RANGE, OFF -> false;
		};
	}

	/**
	 * With monster_behavior SHORT_RANGE, a monster the disguise would fool notices the player from this fraction of its
	 * usual range (like a player wearing its head), then attacks as usual. 1 otherwise.
	 */
	public static double visibility(LivingEntity target, @Nullable Entity looker) {
		return looker instanceof LivingEntity attacker && behavior(attacker) == MorphRules.MonsterBehavior.SHORT_RANGE
			&& fooled(attacker, target) ? SHORT_RANGE_VISIBILITY : 1.0;
	}

	/** A monster sizing up a player it has no grudge against: the only case where looking like a mob can matter. */
	private static boolean disguiseApplies(LivingEntity attacker, LivingEntity target) {
		return attacker instanceof Enemy && target instanceof Player && attacker.getLastHurtByMob() != target
			// Fleeing goes through canAttack too: a creeper that "can't attack" a cat can't see it to run from it.
			&& !(attacker instanceof Creeper && scaresCreepers(target));
	}

	/** The DISGUISE rule: the monster doesn't hunt the mob the player looks like. */
	private static boolean fooled(LivingEntity attacker, LivingEntity target) {
		if (!disguiseApplies(attacker, target)) {
			return false;
		}
		Player player = (Player) target;
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
		// Piglins fight wither skeletons and withers on sight (their nemeses), and piglins hunt hoglins (PiglinAi).
		if (attacker instanceof AbstractPiglin) {
			return body instanceof WitherSkeleton || body instanceof WitherBoss
				|| attacker.getType() == EntityTypes.PIGLIN && body.getType() == EntityTypes.HOGLIN;
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

	/**
	 * How close a villager lets this player come before panicking, if the player looks like something villagers run
	 * from (zombies, illagers, ravagers, vexes: VillagerHostilesSensor's list). Null if villagers don't mind them.
	 */
	public static @Nullable Float villagersFear(LivingEntity entity) {
		if (!(entity instanceof Player player) || behavior(player) == MorphRules.MonsterBehavior.OFF) {
			return null;
		}
		EntityType<?> type = MorphState.current(player);
		return type == null ? null : VillagerHostilesSensorAccessor.morph$fearDistances().get(type);
	}

	/** Endermen don't take a look from an enderman as a challenge. */
	public static boolean isFellowEnderman(Player player) {
		return MorphState.current(player) == EntityTypes.ENDERMAN && behavior(player) != MorphRules.MonsterBehavior.OFF;
	}

	/** A cat-morphed player within 16 blocks, which sends a swooping phantom off like a real cat does. */
	public static boolean catPlayerNear(Mob phantom) {
		if (behavior(phantom) == MorphRules.MonsterBehavior.OFF) {
			return false;
		}
		for (Player player : phantom.level().players()) {
			if (MorphState.current(player) == EntityTypes.CAT && player.isAlive() && !player.isSpectator()
				&& phantom.getBoundingBox().inflate(16.0).intersects(player.getBoundingBox())) {
				return true;
			}
		}
		return false;
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

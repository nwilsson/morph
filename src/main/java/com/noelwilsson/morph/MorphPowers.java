package com.noelwilsson.morph;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.noelwilsson.morph.mixin.MobAccessor;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Active powers (the "use power" key) and melee effects, modelled on what each mob does in vanilla. Powers run on the
 * server; the client only sends "use" and draws the cooldown from the synced attachment.
 */
public final class MorphPowers {
	/**
	 * Game time each mob's power is ready again, by mob id. It stays when the player changes body, so morphing away
	 * and back can't skip a cooldown (chicken, pig, chicken used to lay an egg every time). Saved with the player.
	 */
	public static final AttachmentType<Map<String, Long>> READY_BY_MOB = AttachmentRegistry.<Map<String, Long>>builder()
		.persistent(Codec.unboundedMap(Codec.STRING, Codec.LONG))
		.buildAndRegister(Morph.id("ready_by_mob"));
	/** Game time when the current body's power can be used again. Synced to the owner for the HUD. */
	public static final AttachmentType<Long> READY_AT = AttachmentRegistry.<Long>builder()
		.syncWith(ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Morph.id("ready_at"));
	/** Cooldown length of the current body's power, so the HUD can draw a fraction. */
	public static final AttachmentType<Integer> COOLDOWN = AttachmentRegistry.<Integer>builder()
		.syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Morph.id("cooldown"));

	@FunctionalInterface
	public interface Action {
		/** Returns false if the power didn't fire (no target, nowhere to go), so no cooldown is charged. */
		boolean use(ServerPlayer player, ServerLevel level);
	}

	public record Power(String name, int cooldown, Action action) {}

	private static final Map<String, Power> POWERS = new HashMap<>();
	/** Game time each gliding player can flap again. */
	private static final Map<UUID, Long> NEXT_FLAP = new HashMap<>();
	public static final int FLAP_COOLDOWN = 20;
	private static final double FLAP_FORWARD = 0.2;
	private static final double FLAP_UP = 0.4;
	/** Firework-boosted elytra flight tops out around 1.7 blocks a tick; flapping stays under it. */
	private static final double FLAP_MAX_SPEED = 1.0;
	/** Creepers explode after a fuse, like the real thing. */
	private static final Map<UUID, Long> FUSES = new HashMap<>();
	private static final int CREEPER_FUSE = 30;
	/** The sonic boom fires partway through the warden's wind-up animation, like the real one (SonicBoom). */
	private static final Map<UUID, Long> SONIC_BOOMS = new HashMap<>();
	private static final int SONIC_BOOM_DELAY = 34;

	static {
		power("blaze", "Fireballs", 40, (p, l) -> {
			for (int i = 0; i < 3; i++) {
				Vec3 dir = spread(p, 0.06);
				shoot(p, l, new SmallFireball(l, p, dir), dir);
			}
			return sound(p, l, SoundEvents.BLAZE_SHOOT);
		});
		power("ghast", "Fireball", 60, (p, l) -> {
			Vec3 dir = p.getLookAngle();
			shoot(p, l, new LargeFireball(l, p, dir, 1), dir);
			return sound(p, l, SoundEvents.GHAST_SHOOT);
		});
		power("wither", "Wither skull", 20, (p, l) -> {
			Vec3 dir = p.getLookAngle();
			shoot(p, l, new WitherSkull(l, p, dir), dir);
			return sound(p, l, SoundEvents.WITHER_SHOOT);
		});
		power("ender_dragon", "Dragon's breath", 60, (p, l) -> {
			Vec3 dir = p.getLookAngle();
			shoot(p, l, new DragonFireball(l, p, dir), dir);
			return sound(p, l, SoundEvents.ENDER_DRAGON_SHOOT);
		});
		power("enderman", "Teleport", 20, MorphPowers::enderTeleport);
		power("shulker", "Homing bullet", 40, (p, l) -> {
			LivingEntity target = target(p, 24);
			if (target == null) {
				return fail(p, "No target in sight");
			}
			ShulkerBullet bullet = new ShulkerBullet(l, p, target, Direction.Axis.Y);
			bullet.setPos(p.getEyePosition().add(p.getLookAngle()));
			l.addFreshEntity(bullet);
			return sound(p, l, SoundEvents.SHULKER_SHOOT);
		});
		power("creeper", "Explode", 200, (p, l) -> {
			FUSES.put(p.getUUID(), l.getGameTime() + CREEPER_FUSE);
			return sound(p, l, SoundEvents.CREEPER_PRIMED);
		});
		power("skeleton", "Arrow", 15, (p, l) -> arrow(p, l, null));
		power("stray", "Frost arrow", 15, (p, l) -> arrow(p, l, new MobEffectInstance(MobEffects.SLOWNESS, 600)));
		power("bogged", "Poison arrow", 15, (p, l) -> arrow(p, l, new MobEffectInstance(MobEffects.POISON, 100)));
		power("snow_golem", "Snowball", 5, (p, l) -> {
			throwItem(p, l, new Snowball(l, p, new ItemStack(Items.SNOWBALL)), 1.6F);
			return sound(p, l, SoundEvents.SNOW_GOLEM_SHOOT);
		});
		power("witch", "Splash potion", 40, (p, l) -> {
			ItemStack potion = PotionContents.createItemStack(Items.SPLASH_POTION, p.isShiftKeyDown() ? Potions.POISON : Potions.HARMING);
			throwItem(p, l, new ThrownSplashPotion(l, p, potion), 0.75F);
			return sound(p, l, SoundEvents.WITCH_THROW);
		});
		power("evoker", "Fangs", 60, (p, l) -> {
			float yaw = (float) Math.toRadians(p.getYRot() + 90.0F);
			for (int i = 1; i <= 12; i++) {
				double x = p.getX() + Mth.cos(yaw) * 1.25 * i;
				double z = p.getZ() + Mth.sin(yaw) * 1.25 * i;
				l.addFreshEntity(new EvokerFangs(l, x, p.getY(), z, yaw, i, p));
			}
			return sound(p, l, SoundEvents.EVOKER_CAST_SPELL);
		});
		power("breeze", "Wind charge", 20, (p, l) -> {
			WindCharge charge = new WindCharge(p, l, p.getX(), p.getEyeY(), p.getZ());
			throwItem(p, l, charge, 1.5F);
			return sound(p, l, SoundEvents.BREEZE_SHOOT);
		});
		Action spit = (p, l) -> {
			LlamaSpit projectile = new LlamaSpit(EntityTypes.LLAMA_SPIT, l);
			projectile.setOwner(p);
			throwItem(p, l, projectile, 1.5F);
			return sound(p, l, SoundEvents.LLAMA_SPIT);
		};
		power("llama", "Spit", 20, spit);
		power("trader_llama", "Spit", 20, spit);
		power("warden", "Sonic boom", 100, (p, l) -> {
			SONIC_BOOMS.put(p.getUUID(), l.getGameTime() + SONIC_BOOM_DELAY);
			MorphAnimationPayload.broadcast(p, MorphAnimationPayload.SONIC_BOOM);
			return sound(p, l, SoundEvents.WARDEN_SONIC_CHARGE);
		});
		power("guardian", "Laser", 40, (p, l) -> laser(p, l, 6.0F, false));
		power("elder_guardian", "Laser", 40, (p, l) -> laser(p, l, 8.0F, true));
		power("drowned", "Trident", 30, (p, l) -> {
			ThrownTrident trident = new ThrownTrident(l, p, new ItemStack(Items.TRIDENT));
			trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
			throwItem(p, l, trident, 2.5F);
			return sound(p, l, SoundEvents.DROWNED_SHOOT);
		});
		power("chicken", "Lay egg", 600, (p, l) -> {
			ItemEntity egg = new ItemEntity(l, p.getX(), p.getY(), p.getZ(), new ItemStack(Items.EGG));
			egg.setDefaultPickUpDelay(); // laid on the ground, not straight into the pocket
			l.addFreshEntity(egg);
			return sound(p, l, SoundEvents.CHICKEN_EGG);
		});
		power("squid", "Ink cloud", 100, (p, l) -> ink(p, l, SoundEvents.SQUID_SQUIRT));
		power("glow_squid", "Ink cloud", 100, (p, l) -> ink(p, l, SoundEvents.GLOW_SQUID_SQUIRT));
		power("pufferfish", "Puff up", 60, (p, l) -> {
			for (LivingEntity e : nearby(p, l, 3.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.POISON, 120, 0), p);
				e.hurtServer(l, p.damageSources().mobAttack(p), 2.0F);
			}
			return sound(p, l, SoundEvents.PUFFER_FISH_BLOW_UP);
		});
		power("goat", "Ram", 60, (p, l) -> charge(p, l, 1.6, 4.0F, 2.5, SoundEvents.GOAT_RAM_IMPACT));
		power("camel", "Dash", 60, (p, l) -> {
			Vec3 look = flatLook(p);
			setMotion(p, new Vec3(look.x * 2.2, 0.5, look.z * 2.2));
			return sound(p, l, SoundEvents.CAMEL_DASH);
		});
		power("ravager", "Roar", 80, (p, l) -> {
			for (LivingEntity e : nearby(p, l, 4.0)) {
				e.hurtServer(l, p.damageSources().mobAttack(p), 6.0F);
				Vec3 away = e.position().subtract(p.position()).normalize();
				knock(e, away.x * 1.5, 0.3, away.z * 1.5);
			}
			l.sendParticles(ParticleTypes.POOF, p.getX(), p.getY() + 1, p.getZ(), 40, 2, 0.5, 2, 0.1);
			return sound(p, l, SoundEvents.RAVAGER_ROAR);
		});
		power("iron_golem", "Toss", 20, (p, l) -> toss(p, l, 10.0F, SoundEvents.IRON_GOLEM_ATTACK));
		// Hoglins throw what they hit into the air (Hoglin.throwTarget).
		power("hoglin", "Toss", 40, (p, l) -> toss(p, l, attackDamage(p), SoundEvents.HOGLIN_ATTACK));
		power("zoglin", "Toss", 40, (p, l) -> toss(p, l, attackDamage(p), SoundEvents.ZOGLIN_ATTACK));
		power("armadillo", "Roll up", 200, (p, l) -> {
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 3));
			p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 3));
			return sound(p, l, SoundEvents.ARMADILLO_ROLL);
		});
		power("axolotl", "Play dead", 1200, (p, l) -> {
			p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
			return sound(p, l, SoundEvents.AXOLOTL_ATTACK);
		});
		power("frog", "Tongue", 40, (p, l) -> {
			LivingEntity target = target(p, 8);
			if (target == null) {
				return fail(p, "Nothing to grab");
			}
			Vec3 pull = p.position().subtract(target.position()).normalize().scale(1.2);
			knock(target, pull.x, 0.3, pull.z);
			return sound(p, l, SoundEvents.FROG_TONGUE);
		});

		// Pounce: LeapAtTargetGoal, at whatever you're looking at (or straight ahead), a bit stronger than the mob's own.
		Action pounce = (p, l) -> leap(p, l, 1.0, 0.45, true);
		for (String mob : List.of("wolf", "cat", "ocelot", "spider", "cave_spider")) {
			power(mob, "Pounce", 40, pounce);
		}
		// Foxes pounce high, onto prey from above (FoxPounceGoal).
		power("fox", "Pounce", 40, (p, l) -> leap(p, l, 0.8, 0.9, true));
		// Horses: the charged jump, forward and up.
		Action horseLeap = (p, l) -> leap(p, l, 1.2, 0.9, false);
		for (String mob : List.of("horse", "donkey", "mule", "skeleton_horse", "zombie_horse")) {
			power(mob, "Leap", 40, horseLeap);
		}
		power("dolphin", "Dash", 40, (p, l) -> {
			if (!p.isInWater()) {
				return fail(p, "Only in water");
			}
			setMotion(p, p.getLookAngle().scale(1.8));
			return sound(p, l, SoundEvents.DOLPHIN_JUMP);
		});
		// Nautiluses dash where their rider looks, hard in water and weakly on land (AbstractNautilus.executeRidersJump).
		power("nautilus", "Dash", 40, (p, l) -> nautilusDash(p, l, SoundEvents.NAUTILUS_DASH, SoundEvents.NAUTILUS_DASH_ON_LAND));
		power("zombie_nautilus", "Dash", 40, (p, l) -> nautilusDash(p, l, SoundEvents.ZOMBIE_NAUTILUS_DASH, SoundEvents.ZOMBIE_NAUTILUS_DASH_ON_LAND));
		power("turtle", "Shell", 200, (p, l) -> {
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 2));
			p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 2));
			return sound(p, l, SoundEvents.ARMOR_EQUIP_TURTLE.value());
		});
		Action crossbow = (p, l) -> arrow(p, l, null, 3.15F, SoundEvents.CROSSBOW_SHOOT);
		power("pillager", "Crossbow", 25, crossbow);
		power("piglin", "Crossbow", 25, crossbow);
		// The illusioner's two spells: turn invisible, blind whoever is near.
		power("illusioner", "Mirror image", 200, (p, l) -> {
			p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0));
			for (LivingEntity e : nearby(p, l, 8.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0), p);
			}
			l.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.6, 0.8, 0.6, 0.02);
			return sound(p, l, SoundEvents.ILLUSIONER_MIRROR_MOVE);
		});
		// Phantoms swoop and vexes charge: fly the way you look and hit what's in the path.
		power("phantom", "Swoop", 40, (p, l) -> charge(p, l, p.getLookAngle(), 1.6, attackDamage(p), 1.0, SoundEvents.PHANTOM_SWOOP));
		power("vex", "Charge", 40, (p, l) -> charge(p, l, p.getLookAngle(), 1.4, attackDamage(p), 0.8, SoundEvents.VEX_CHARGE));
		power("polar_bear", "Swipe", 30, (p, l) -> {
			Vec3 look = flatLook(p);
			AABB front = p.getBoundingBox().expandTowards(look.scale(2.5)).inflate(0.5);
			List<Entity> hit = l.getEntities(p, front, e -> e instanceof LivingEntity && e.isAlive());
			if (hit.isEmpty()) {
				return fail(p, "Nothing in reach");
			}
			for (Entity e : hit) {
				((LivingEntity) e).hurtServer(l, p.damageSources().mobAttack(p), attackDamage(p));
				knock(e, look.x * 1.2, 0.3, look.z * 1.2);
			}
			return sound(p, l, SoundEvents.POLAR_BEAR_WARNING);
		});
		// Sheep graze: grass under you turns to dirt (EatBlockGoal), and it feeds you a little.
		power("sheep", "Eat grass", 100, (p, l) -> {
			BlockPos feet = p.blockPosition();
			BlockPos below = feet.below();
			if (l.getBlockState(feet).is(BlockTags.EDIBLE_FOR_SHEEP)) {
				l.destroyBlock(feet, false);
			} else if (l.getBlockState(below).is(Blocks.GRASS_BLOCK)) {
				l.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, below, Block.getId(Blocks.GRASS_BLOCK.defaultBlockState()));
				l.setBlock(below, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
			} else {
				return fail(p, "No grass here");
			}
			p.heal(2.0F);
			p.getFoodData().eat(2, 0.3F);
			return sound(p, l, SoundEvents.GENERIC_EAT.value());
		});
		// Sniffers dig up ancient seeds from the sniffer's own loot table.
		power("sniffer", "Dig", 2400, (p, l) -> {
			BlockPos below = p.blockPosition().below();
			if (!l.getBlockState(below).is(BlockTags.SNIFFER_DIGGABLE_BLOCK)) {
				return fail(p, "Nothing to dig here");
			}
			p.dropFromGiftLootTable(l, BuiltInLootTables.SNIFFER_DIGGING, (level, stack) -> {
				ItemEntity seed = new ItemEntity(level, p.getX(), p.getY() + 0.2, p.getZ(), stack);
				seed.setDefaultPickUpDelay();
				level.addFreshEntity(seed);
			});
			l.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, below, Block.getId(l.getBlockState(below)));
			return sound(p, l, SoundEvents.SNIFFER_DROP_SEED);
		});
	}

	private MorphPowers() {}

	public static void init() {
		// Class loading registers the attachments.
	}

	private static void power(String mob, String name, int cooldown, Action action) {
		POWERS.put(mob, new Power(name, cooldown, action));
	}

	public static @Nullable Power of(@Nullable EntityType<?> type) {
		if (type == null) {
			return null;
		}
		var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return "minecraft".equals(id.getNamespace()) ? POWERS.get(id.getPath()) : null;
	}

	private static String key(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
	}

	/** Called when the client presses the power key. */
	public static void use(ServerPlayer player) {
		EntityType<?> type = MorphState.current(player);
		Power power = of(type);
		if (power == null || !player.isAlive() || player.isSpectator()) {
			return;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		long readyAt = player.getAttachedOrElse(READY_BY_MOB, Map.of()).getOrDefault(key(type), 0L);
		if (now < readyAt) {
			player.sendOverlayMessage(Component.literal(power.name() + " ready in " + String.format("%.1f", (readyAt - now) / 20.0) + "s"));
			return;
		}
		if (power.action().use(player, level)) {
			Map<String, Long> ready = new HashMap<>(player.getAttachedOrElse(READY_BY_MOB, Map.of()));
			ready.values().removeIf(time -> time <= now);
			ready.put(key(type), now + power.cooldown());
			player.setAttached(READY_BY_MOB, ready);
			showCooldown(player);
		}
	}

	/**
	 * After a change of body: cancels anything the old body had started (a creeper's fuse, a warden's wind-up) and
	 * shows the new body's cooldown, which carries on from whenever that mob's power was last used.
	 */
	public static void changedBody(ServerPlayer player) {
		FUSES.remove(player.getUUID());
		SONIC_BOOMS.remove(player.getUUID());
		NEXT_FLAP.remove(player.getUUID());
		showCooldown(player);
	}

	private static void showCooldown(ServerPlayer player) {
		EntityType<?> type = MorphState.current(player);
		Power power = of(type);
		long readyAt = power == null ? 0L : player.getAttachedOrElse(READY_BY_MOB, Map.of()).getOrDefault(key(type), 0L);
		if (readyAt > player.level().getGameTime()) {
			player.setAttached(READY_AT, readyAt);
			player.setAttached(COOLDOWN, power.cooldown());
		} else {
			player.removeAttached(READY_AT);
			player.removeAttached(COOLDOWN);
		}
	}

	/**
	 * A wingbeat while gliding: a push up and along the look, like a weak firework, at most once every
	 * {@link #FLAP_COOLDOWN} ticks. Enough to take off from flat ground and climb, slower than rockets.
	 */
	public static void flap(ServerPlayer player) {
		EntityType<?> type = MorphState.current(player);
		if (type == null || !player.isFallFlying() || !MorphAbilities.of(type).contains(MorphAbilities.Ability.GLIDE)) {
			return;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		if (now < NEXT_FLAP.getOrDefault(player.getUUID(), 0L)) {
			return;
		}
		NEXT_FLAP.put(player.getUUID(), now + FLAP_COOLDOWN);
		Vec3 motion = player.getDeltaMovement().add(player.getLookAngle().scale(FLAP_FORWARD)).add(0, FLAP_UP, 0);
		if (motion.length() > FLAP_MAX_SPEED) {
			motion = motion.normalize().scale(FLAP_MAX_SPEED);
		}
		setMotion(player, motion);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	/** Every power ready now. For tests; changing body never does this. */
	public static void clearCooldowns(ServerPlayer player) {
		player.removeAttached(READY_BY_MOB);
		showCooldown(player);
	}

	public static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, Long>> it = SONIC_BOOMS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> boom = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(boom.getKey());
			if (player == null || MorphState.current(player) != EntityTypes.WARDEN || !player.isAlive()) {
				it.remove();
			} else if (player.level().getGameTime() >= boom.getValue()) {
				it.remove();
				sonicBoom(player, player.level());
			}
		}
		for (Iterator<Map.Entry<UUID, Long>> it = FUSES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> fuse = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(fuse.getKey());
			if (player == null || MorphState.current(player) != EntityTypes.CREEPER || !player.isAlive()) {
				it.remove();
				continue;
			}
			ServerLevel level = player.level();
			if (level.getGameTime() >= fuse.getValue()) {
				it.remove();
				// Like a creeper, but the player survives their own blast.
				level.explode(player, null, new ExplosionDamageCalculator() {
					@Override
					public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
						return entity != player;
					}
				}, player.position(), 3.0F, false, Level.ExplosionInteraction.MOB);
			} else {
				level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1, player.getZ(), 4, 0.3, 0.5, 0.3, 0.01);
			}
		}
	}

	/** Melee hits by a morphed player carry the mob's own on-hit effect. */
	public static void onMeleeHit(ServerPlayer player, LivingEntity target) {
		EntityType<?> type = MorphState.current(player);
		if (type == null) {
			return;
		}
		var id = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
		switch (id) {
			case "bee", "cave_spider" -> target.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0), player);
			case "wither_skeleton" -> target.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 0), player);
			case "wither" -> target.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 1), player);
			case "husk" -> target.addEffect(new MobEffectInstance(MobEffects.HUNGER, 140, 0), player);
			case "blaze", "magma_cube" -> target.igniteForSeconds(5.0F);
			case "iron_golem", "hoglin", "zoglin", "ravager" -> {
				knock(target, 0, 0.4, 0);
			}
			default -> {
			}
		}
	}

	// --- power helpers ---

	private static boolean enderTeleport(ServerPlayer p, ServerLevel l) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(32));
		BlockHitResult hit = l.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 from = p.position();
		boolean moved;
		if (hit.getType() == HitResult.Type.BLOCK) {
			// Land on the face we looked at, standing on top when it's a floor.
			BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
			moved = p.randomTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, true, BlockTags.ENDERMAN_DOES_NOT_TELEPORT_TO);
		} else {
			moved = p.randomTeleport(end.x, end.y, end.z, true, BlockTags.ENDERMAN_DOES_NOT_TELEPORT_TO);
		}
		if (!moved) {
			moved = p.randomTeleport(p.getX() + (p.getRandom().nextDouble() - 0.5) * 32, p.getY() + p.getRandom().nextInt(16) - 8,
				p.getZ() + (p.getRandom().nextDouble() - 0.5) * 32, true, BlockTags.ENDERMAN_DOES_NOT_TELEPORT_TO);
		}
		if (!moved) {
			return fail(p, "Nowhere to teleport");
		}
		p.resetFallDistance();
		l.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
		return sound(p, l, SoundEvents.ENDERMAN_TELEPORT);
	}

	/** Fires along wherever the player is looking now, so they can aim during the wind-up. */
	private static void sonicBoom(ServerPlayer p, ServerLevel l) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		for (int i = 1; i <= 15; i++) {
			Vec3 at = eye.add(look.scale(i));
			l.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		AABB beam = p.getBoundingBox().expandTowards(look.scale(15)).inflate(1.0);
		for (Entity e : l.getEntities(p, beam, e -> e instanceof LivingEntity && e.isAlive())) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double along = to.dot(look);
			if (along > 0 && along <= 16 && to.subtract(look.scale(along)).length() < 1.5) {
				LivingEntity living = (LivingEntity) e;
				living.hurtServer(l, l.damageSources().sonicBoom(p), 10.0F);
				knock(living, look.x * 2.5, 0.5, look.z * 2.5);
			}
		}
		sound(p, l, SoundEvents.WARDEN_SONIC_BOOM);
	}

	private static boolean laser(ServerPlayer p, ServerLevel l, float damage, boolean fatigue) {
		LivingEntity target = target(p, 16);
		if (target == null) {
			return fail(p, "No target in sight");
		}
		Vec3 from = p.getEyePosition();
		Vec3 to = target.getEyePosition();
		for (int i = 0; i <= 16; i++) {
			Vec3 at = from.lerp(to, i / 16.0);
			l.sendParticles(ParticleTypes.BUBBLE, at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0);
		}
		target.hurtServer(l, l.damageSources().indirectMagic(p, p), damage);
		if (fatigue) {
			target.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 1200, 2), p);
		}
		return sound(p, l, SoundEvents.GUARDIAN_ATTACK);
	}

	private static boolean nautilusDash(ServerPlayer p, ServerLevel l, SoundEvent inWater, SoundEvent onLand) {
		setMotion(p, p.getLookAngle().scale(p.isInWater() ? 1.8 : 0.75));
		return sound(p, l, p.isInWater() ? inWater : onLand);
	}

	private static boolean ink(ServerPlayer p, ServerLevel l, SoundEvent sound) {
		for (LivingEntity e : nearby(p, l, 5.0)) {
			e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0), p);
		}
		l.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 0.5, p.getZ(), 60, 1.2, 1.2, 1.2, 0.05);
		p.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1));
		return sound(p, l, sound);
	}

	/** Ground charge: run forward with a little hop. */
	private static boolean charge(ServerPlayer p, ServerLevel l, double speed, float damage, double knockback, SoundEvent sound) {
		Vec3 look = flatLook(p);
		return charge(p, l, new Vec3(look.x, 0.2 / speed, look.z), speed, damage, knockback, sound);
	}

	/** Move along `direction` and hit everything in the 3 blocks ahead. */
	private static boolean charge(ServerPlayer p, ServerLevel l, Vec3 direction, double speed, float damage, double knockback, SoundEvent sound) {
		setMotion(p, direction.scale(speed));
		Vec3 ahead = direction.normalize();
		AABB front = p.getBoundingBox().expandTowards(ahead.scale(3)).inflate(0.5);
		for (Entity e : l.getEntities(p, front, e -> e instanceof LivingEntity && e.isAlive())) {
			LivingEntity living = (LivingEntity) e;
			living.hurtServer(l, p.damageSources().mobAttack(p), damage);
			knock(living, ahead.x * knockback, 0.4, ahead.z * knockback);
		}
		return sound(p, l, sound);
	}

	/**
	 * Jump forward from the ground. With `atTarget`, aim the jump at what the player is looking at (like
	 * LeapAtTargetGoal), otherwise straight ahead. Plays the mob's own voice.
	 */
	private static boolean leap(ServerPlayer p, ServerLevel l, double forward, double up, boolean atTarget) {
		if (!p.onGround() && !p.isInWater()) {
			return fail(p, "Must be on the ground");
		}
		LivingEntity target = atTarget ? target(p, 12) : null;
		Vec3 dir = target == null ? flatLook(p) : new Vec3(target.getX() - p.getX(), 0, target.getZ() - p.getZ());
		dir = dir.lengthSqr() > 1.0E-7 ? dir.normalize() : flatLook(p);
		setMotion(p, new Vec3(dir.x * forward, up, dir.z * forward));
		return voice(p, l);
	}

	/** Damage what's in reach and throw it into the air. */
	private static boolean toss(ServerPlayer p, ServerLevel l, float damage, SoundEvent sound) {
		LivingEntity target = target(p, 4);
		if (target == null) {
			return fail(p, "Nothing in reach");
		}
		target.hurtServer(l, p.damageSources().mobAttack(p), damage);
		knock(target, 0, 0.8, 0);
		return sound(p, l, sound);
	}

	/** The morph's melee damage (MorphState sets it from the mob's attribute). */
	private static float attackDamage(ServerPlayer p) {
		return (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE);
	}

	/** Play the morphed mob's own idle sound; wolves and cats have one per variant. */
	private static boolean voice(ServerPlayer p, ServerLevel l) {
		EntityType<?> type = MorphState.current(p);
		SoundEvent sound = type != null && MorphTemplates.get(type, l) instanceof MobAccessor mob ? mob.morph$ambientSound() : null;
		if (sound != null) {
			sound(p, l, sound);
		}
		return true;
	}

	private static boolean arrow(ServerPlayer p, ServerLevel l, @Nullable MobEffectInstance effect) {
		return arrow(p, l, effect, 2.5F, SoundEvents.SKELETON_SHOOT);
	}

	private static boolean arrow(ServerPlayer p, ServerLevel l, @Nullable MobEffectInstance effect, float velocity, SoundEvent sound) {
		Arrow arrow = new Arrow(l, p, new ItemStack(Items.ARROW), null);
		arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
		if (effect != null) {
			arrow.addEffect(effect);
		}
		throwItem(p, l, arrow, velocity);
		return sound(p, l, sound);
	}

	/** Push an entity. Players move themselves, so they must be told about the new velocity directly. */
	private static void knock(Entity e, double x, double y, double z) {
		e.push(x, y, z);
		e.needsSync = true;
		if (e instanceof ServerPlayer player) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	private static void setMotion(ServerPlayer player, Vec3 motion) {
		player.setDeltaMovement(motion);
		player.connection.send(new ClientboundSetEntityMotionPacket(player));
	}

	/** Thrown/shot projectiles leave from in front of the eyes so big morphs don't hit themselves. */
	private static void throwItem(ServerPlayer p, ServerLevel l, Projectile projectile, float velocity) {
		Vec3 start = p.getEyePosition().add(p.getLookAngle().scale(p.getBbWidth() / 2 + 0.3));
		projectile.setPos(start.x, start.y - 0.1, start.z);
		projectile.shootFromRotation(p, p.getXRot(), p.getYRot(), 0.0F, velocity, 1.0F);
		l.addFreshEntity(projectile);
	}

	private static void shoot(ServerPlayer p, ServerLevel l, AbstractHurtingProjectile projectile, Vec3 direction) {
		Vec3 start = p.getEyePosition().add(direction.scale(p.getBbWidth() / 2 + 0.6));
		projectile.setPos(start.x, start.y - 0.2, start.z);
		l.addFreshEntity(projectile);
	}

	private static Vec3 spread(ServerPlayer p, double amount) {
		Vec3 look = p.getLookAngle();
		var r = p.getRandom();
		return look.add(r.triangle(0, amount), r.triangle(0, amount), r.triangle(0, amount)).normalize();
	}

	private static Vec3 flatLook(ServerPlayer p) {
		Vec3 look = p.getLookAngle();
		return new Vec3(look.x, 0, look.z).normalize();
	}

	/** The living entity the player is looking at, within range, with half a block of aim assist. Walls block it. */
	private static @Nullable LivingEntity target(ServerPlayer p, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(range));
		BlockHitResult wall = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (wall.getType() == HitResult.Type.BLOCK) {
			end = wall.getLocation();
		}
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		AABB search = p.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.5);
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, search, e -> e != p && e.isAlive() && !e.isSpectator())) {
			var hit = e.getBoundingBox().inflate(0.5).clip(eye, end);
			if (hit.isPresent() && eye.distanceToSqr(hit.get()) < bestDistance) {
				bestDistance = eye.distanceToSqr(hit.get());
				best = e;
			}
		}
		return best;
	}

	private static List<LivingEntity> nearby(ServerPlayer p, ServerLevel l, double radius) {
		return l.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(radius), e -> e != p && e.isAlive());
	}

	private static boolean sound(ServerPlayer p, ServerLevel l, SoundEvent sound) {
		l.playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
		return true;
	}

	private static boolean fail(ServerPlayer p, String message) {
		p.sendOverlayMessage(Component.literal(message));
		return false;
	}
}

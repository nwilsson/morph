package com.noelwilsson.morph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import com.noelwilsson.morph.mixin.MobAccessor;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Active powers (the "use power" key) and melee effects. Which mob has which power comes from its data file (see
 * {@link MorphMobs}), which names a power type and its settings:
 * <pre>{"type": "morph:projectile", "entity": "minecraft:small_fireball", "count": 3, "cooldown": 40, "name": "Fireballs"}</pre>
 * Powers run on the server; the client only sends "use" and draws the cooldown from the synced attachment.
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

	/**
	 * A kind of power a data file can name: its settings (the codec) and what a file gets when it leaves out
	 * "name" and "cooldown". Other mods add theirs with {@link #registerType} during init.
	 */
	public record PowerType(Identifier id, String name, int cooldown, MapCodec<? extends Action> codec) {
		public static final Codec<PowerType> CODEC = Identifier.CODEC.comapFlatMap(
			id -> Optional.ofNullable(TYPES.get(id)).map(DataResult::success)
				.orElseGet(() -> DataResult.error(() -> "Unknown morph power type " + id + ", known: " + TYPES.keySet())),
			PowerType::id);

		@SuppressWarnings("unchecked")
		private MapCodec<Power> powerCodec() {
			return RecordCodecBuilder.mapCodec(i -> i.group(
				((MapCodec<Action>) codec).forGetter(Power::action),
				Codec.STRING.optionalFieldOf("name", name).forGetter(Power::name),
				ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("cooldown", cooldown).forGetter(Power::cooldown)
			).apply(i, (action, name, cooldown) -> new Power(this, name, cooldown, action)));
		}
	}

	/** A mob's power: the action and how it's shown and paced. */
	public record Power(PowerType type, String name, int cooldown, Action action) {
		public static final Codec<Power> CODEC = PowerType.CODEC.dispatch("type", Power::type, PowerType::powerCodec);
	}

	private static final Map<Identifier, PowerType> TYPES = new LinkedHashMap<>();
	/** Game time each gliding player can flap again. */
	private static final Map<UUID, Long> NEXT_FLAP = new HashMap<>();
	public static final int FLAP_COOLDOWN = 20;
	private static final double FLAP_FORWARD = 0.2;
	private static final double FLAP_UP = 0.4;
	/** Firework-boosted elytra flight tops out around 1.7 blocks a tick; flapping stays under it. */
	private static final double FLAP_MAX_SPEED = 1.0;
	/** Powers that go off after a wind-up (a creeper's fuse, a warden's charge), by player. */
	private static final Map<UUID, Delayed> DELAYED = new HashMap<>();
	/** The sonic boom fires partway through the warden's wind-up animation, like the real one (SonicBoom). */
	private static final int SONIC_BOOM_DELAY = 34;

	private static final Codec<SoundEvent> SOUND = BuiltInRegistries.SOUND_EVENT.byNameCodec();
	private static final Codec<List<MobEffectInstance>> EFFECTS = MobEffectInstance.CODEC.listOf();

	static {
		// General kinds with settings, for any mob.
		registerType(Morph.id("projectile"), "Shoot", 20, Shoot.CODEC);
		registerType(Morph.id("charge"), "Charge", 40, Charge.CODEC);
		registerType(Morph.id("leap"), "Leap", 40, Leap.CODEC);
		registerType(Morph.id("dash"), "Dash", 40, Dash.CODEC);
		registerType(Morph.id("teleport"), "Teleport", 20, Teleport.CODEC);
		registerType(Morph.id("explode"), "Explode", 200, Explode.CODEC);
		registerType(Morph.id("burst"), "Burst", 100, Burst.CODEC);
		registerType(Morph.id("laser"), "Laser", 40, Laser.CODEC);
		registerType(Morph.id("toss"), "Toss", 40, Toss.CODEC);
		registerType(Morph.id("drop_item"), "Drop", 600, DropItem.CODEC);
		registerType(Morph.id("dig"), "Dig", 2400, Dig.CODEC);

		// One mob's own move, with nothing to set.
		fixed("homing_bullet", "Homing bullet", 40, (p, l) -> {
			LivingEntity target = target(p, 24);
			if (target == null) {
				return fail(p, "No target in sight");
			}
			ShulkerBullet bullet = new ShulkerBullet(l, p, target, Direction.Axis.Y);
			bullet.setPos(p.getEyePosition().add(p.getLookAngle()));
			l.addFreshEntity(bullet);
			return play(p, l, SoundEvents.SHULKER_SHOOT);
		});
		// Harming, or poison while sneaking.
		fixed("splash_potion", "Splash potion", 40, (p, l) -> {
			ItemStack potion = PotionContents.createItemStack(Items.SPLASH_POTION, p.isShiftKeyDown() ? Potions.POISON : Potions.HARMING);
			throwItem(p, l, new ThrownSplashPotion(l, p, potion), 0.75F);
			return play(p, l, SoundEvents.WITCH_THROW);
		});
		fixed("fangs", "Fangs", 60, (p, l) -> {
			float yaw = (float) Math.toRadians(p.getYRot() + 90.0F);
			for (int i = 1; i <= 12; i++) {
				double x = p.getX() + Mth.cos(yaw) * 1.25 * i;
				double z = p.getZ() + Mth.sin(yaw) * 1.25 * i;
				l.addFreshEntity(new EvokerFangs(l, x, p.getY(), z, yaw, i, p));
			}
			return play(p, l, SoundEvents.EVOKER_CAST_SPELL);
		});
		fixed("sonic_boom", "Sonic boom", 100, (p, l) -> {
			later(p, SONIC_BOOM_DELAY, player -> sonicBoom(player, player.level()), null);
			MorphAnimationPayload.broadcast(p, MorphAnimationPayload.SONIC_BOOM);
			return play(p, l, SoundEvents.WARDEN_SONIC_CHARGE);
		});
		fixed("tongue", "Tongue", 40, (p, l) -> {
			LivingEntity target = target(p, 8);
			if (target == null) {
				return fail(p, "Nothing to grab");
			}
			Vec3 pull = p.position().subtract(target.position()).normalize().scale(1.2);
			knock(target, pull.x, 0.3, pull.z);
			return play(p, l, SoundEvents.FROG_TONGUE);
		});
		fixed("swipe", "Swipe", 30, (p, l) -> {
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
			return play(p, l, SoundEvents.POLAR_BEAR_WARNING);
		});
		// Sheep graze: grass under you turns to dirt (EatBlockGoal), and it feeds you a little.
		fixed("graze", "Eat grass", 100, (p, l) -> {
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
			return play(p, l, SoundEvents.GENERIC_EAT.value());
		});
	}

	private MorphPowers() {}

	public static void init() {
		// Class loading registers the attachments and the power types.
	}

	/**
	 * Adds a power type data files can name. Call it from a mod initializer, before any data loads. Its default name
	 * and cooldown are used when a file leaves them out.
	 */
	public static void registerType(Identifier id, String name, int cooldown, MapCodec<? extends Action> codec) {
		if (TYPES.putIfAbsent(id, new PowerType(id, name, cooldown, codec)) != null) {
			throw new IllegalArgumentException("Morph power type " + id + " is already registered");
		}
	}

	private static void fixed(String path, String name, int cooldown, Action action) {
		registerType(Morph.id(path), name, cooldown, MapCodec.unit(action));
	}

	/** Server side: the power this body has, or null. */
	public static @Nullable Power of(@Nullable EntityType<?> type) {
		if (type == null || MorphRules.isPowerless(type)) {
			return null;
		}
		MorphMobs.Mob mob = MorphMobs.get(type);
		return mob == null ? null : mob.power().orElse(null);
	}

	/** Either side: the name of the power this body has, or null if it has none. */
	public static @Nullable String name(@Nullable EntityType<?> type, Level level) {
		if (type == null || MorphRules.isPowerless(type)) {
			return null;
		}
		MorphMobs.Synced mob = MorphMobs.synced(type, level);
		return mob == null ? null : mob.power().orElse(null);
	}

	private static String key(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
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
		DELAYED.remove(player.getUUID());
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
		ServerLevel level = player.level();
		if (type == null || !player.isFallFlying() || !MorphAbilities.of(type, level).contains(MorphAbilities.Ability.GLIDE)) {
			return;
		}
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

	/** A wound-up power: fires at {@code at} if the player is still alive in the same body, ticking {@code meanwhile} until then. */
	private record Delayed(EntityType<?> body, long at, Consumer<ServerPlayer> fire, @Nullable Consumer<ServerPlayer> meanwhile) {}

	private static void later(ServerPlayer player, int ticks, Consumer<ServerPlayer> fire, @Nullable Consumer<ServerPlayer> meanwhile) {
		DELAYED.put(player.getUUID(), new Delayed(MorphState.current(player), player.level().getGameTime() + ticks, fire, meanwhile));
	}

	public static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, Delayed>> it = DELAYED.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Delayed> entry = it.next();
			Delayed delayed = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || MorphState.current(player) != delayed.body() || !player.isAlive()) {
				it.remove();
			} else if (player.level().getGameTime() >= delayed.at()) {
				it.remove();
				delayed.fire().accept(player);
			} else if (delayed.meanwhile() != null) {
				delayed.meanwhile().accept(player);
			}
		}
	}

	/** Melee hits by a morphed player carry the mob's own on-hit effect (its file's "on_hit"). */
	public static void onMeleeHit(ServerPlayer player, LivingEntity target) {
		EntityType<?> type = MorphState.current(player);
		MorphMobs.Mob mob = type == null ? null : MorphMobs.get(type);
		if (mob == null || mob.onHit().isEmpty()) {
			return;
		}
		MorphMobs.OnHit hit = mob.onHit().get();
		for (MobEffectInstance effect : hit.effects()) {
			target.addEffect(new MobEffectInstance(effect), player);
		}
		if (hit.fireSeconds() > 0) {
			target.igniteForSeconds(hit.fireSeconds());
		}
		if (hit.launch() > 0) {
			knock(target, 0, hit.launch(), 0);
		}
	}

	// --- power types with settings ---

	/**
	 * Shoots {@code count} of any projectile entity. Fireballs and skulls (which steer themselves) fly where the
	 * player looks; anything else, or anything given a {@code speed}, is thrown like an arrow. Arrows can carry effects.
	 */
	private record Shoot(EntityType<?> entity, int count, float spread, Optional<Float> speed, List<MobEffectInstance> effects,
		Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Shoot> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("entity").forGetter(Shoot::entity),
			ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(Shoot::count),
			ExtraCodecs.NON_NEGATIVE_FLOAT.optionalFieldOf("spread", 0.0F).forGetter(Shoot::spread),
			ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("speed").forGetter(Shoot::speed),
			EFFECTS.optionalFieldOf("effects", List.of()).forGetter(Shoot::effects),
			SOUND.optionalFieldOf("sound").forGetter(Shoot::sound)
		).apply(i, Shoot::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			for (int n = 0; n < count; n++) {
				if (!(entity.create(l, EntitySpawnReason.TRIGGERED) instanceof Projectile projectile)) {
					Morph.LOGGER.warn("Morph power for {} shoots {}, which isn't a projectile", MorphState.current(p), entity);
					return fail(p, "This power is broken, see the server log");
				}
				projectile.setOwner(p);
				if (projectile instanceof AbstractArrow arrow) {
					arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
				}
				if (projectile instanceof Arrow arrow) {
					effects.forEach(effect -> arrow.addEffect(new MobEffectInstance(effect)));
				}
				if (speed.isEmpty() && projectile instanceof AbstractHurtingProjectile steered) {
					Vec3 dir = spread > 0 ? MorphPowers.spread(p, spread) : p.getLookAngle();
					steered.setYRot(p.getYRot());
					steered.setXRot(p.getXRot());
					steered.setDeltaMovement(dir.normalize().scale(steered.accelerationPower));
					shoot(p, l, steered, dir);
				} else {
					throwItem(p, l, projectile, speed.orElse(1.5F));
				}
			}
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}
	}

	/** Run (or with {@code flat} false, fly) the way the player looks and hit everything in the 3 blocks ahead. */
	private record Charge(double speed, Optional<Float> damage, double knockback, boolean flat, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Charge> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("speed", 1.5).forGetter(Charge::speed),
			ExtraCodecs.NON_NEGATIVE_FLOAT.optionalFieldOf("damage").forGetter(Charge::damage),
			Codec.DOUBLE.optionalFieldOf("knockback", 1.0).forGetter(Charge::knockback),
			Codec.BOOL.optionalFieldOf("flat", true).forGetter(Charge::flat),
			SOUND.optionalFieldOf("sound").forGetter(Charge::sound)
		).apply(i, Charge::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			Vec3 direction;
			if (flat) {
				// Along the ground with a little hop.
				Vec3 look = flatLook(p);
				direction = new Vec3(look.x, 0.2 / speed, look.z);
			} else {
				direction = p.getLookAngle();
			}
			setMotion(p, direction.scale(speed));
			Vec3 ahead = direction.normalize();
			AABB front = p.getBoundingBox().expandTowards(ahead.scale(3)).inflate(0.5);
			float hurt = damage.orElseGet(() -> attackDamage(p));
			for (Entity e : l.getEntities(p, front, e -> e instanceof LivingEntity && e.isAlive())) {
				LivingEntity living = (LivingEntity) e;
				living.hurtServer(l, p.damageSources().mobAttack(p), hurt);
				knock(living, ahead.x * knockback, 0.4, ahead.z * knockback);
			}
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}
	}

	/**
	 * Jump forward from the ground. With {@code at_target}, at what the player is looking at (like LeapAtTargetGoal),
	 * otherwise straight ahead. Without a sound, the mob's own voice.
	 */
	private record Leap(double forward, double up, boolean atTarget, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Leap> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("forward", 1.0).forGetter(Leap::forward),
			Codec.DOUBLE.optionalFieldOf("up", 0.45).forGetter(Leap::up),
			Codec.BOOL.optionalFieldOf("at_target", false).forGetter(Leap::atTarget),
			SOUND.optionalFieldOf("sound").forGetter(Leap::sound)
		).apply(i, Leap::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			if (!p.onGround() && !p.isInWater()) {
				return fail(p, "Must be on the ground");
			}
			LivingEntity target = atTarget ? target(p, 12) : null;
			Vec3 dir = target == null ? flatLook(p) : new Vec3(target.getX() - p.getX(), 0, target.getZ() - p.getZ());
			dir = dir.lengthSqr() > 1.0E-7 ? dir.normalize() : flatLook(p);
			setMotion(p, new Vec3(dir.x * forward, up, dir.z * forward));
			return sound.isPresent() ? play(p, l, sound.get()) : voice(p, l);
		}
	}

	/**
	 * A burst of speed where the player looks, or along the ground with {@code flat} (plus {@code lift} upward).
	 * {@code land_speed} and {@code land_sound} are used out of water; {@code needs_water} refuses on land.
	 */
	private record Dash(double speed, double lift, boolean flat, boolean needsWater, Optional<Double> landSpeed,
		Optional<SoundEvent> sound, Optional<SoundEvent> landSound) implements Action {
		static final MapCodec<Dash> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("speed", 1.8).forGetter(Dash::speed),
			Codec.DOUBLE.optionalFieldOf("lift", 0.0).forGetter(Dash::lift),
			Codec.BOOL.optionalFieldOf("flat", false).forGetter(Dash::flat),
			Codec.BOOL.optionalFieldOf("needs_water", false).forGetter(Dash::needsWater),
			Codec.DOUBLE.optionalFieldOf("land_speed").forGetter(Dash::landSpeed),
			SOUND.optionalFieldOf("sound").forGetter(Dash::sound),
			SOUND.optionalFieldOf("land_sound").forGetter(Dash::landSound)
		).apply(i, Dash::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			boolean wet = p.isInWater();
			if (needsWater && !wet) {
				return fail(p, "Only in water");
			}
			double s = wet ? speed : landSpeed.orElse(speed);
			setMotion(p, flat ? flatLook(p).scale(s).add(0, lift, 0) : p.getLookAngle().scale(s));
			(wet ? sound : landSound.or(() -> sound)).ifPresent(sound -> play(p, l, sound));
			return true;
		}
	}

	/** The enderman's: to the block looked at within {@code range}, or somewhere random nearby. */
	private record Teleport(double range) implements Action {
		static final MapCodec<Teleport> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("range", 32.0).forGetter(Teleport::range)
		).apply(i, Teleport::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			return enderTeleport(p, l, range);
		}
	}

	/** Like a creeper: smoke for {@code fuse} ticks, then a blast the player survives. */
	private record Explode(float power, int fuse, boolean fire, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Explode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("power", 3.0F).forGetter(Explode::power),
			ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("fuse", 30).forGetter(Explode::fuse),
			Codec.BOOL.optionalFieldOf("fire", false).forGetter(Explode::fire),
			SOUND.optionalFieldOf("sound").forGetter(Explode::sound)
		).apply(i, Explode::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			later(p, fuse, this::blast, player -> player.level().sendParticles(ParticleTypes.SMOKE,
				player.getX(), player.getY() + 1, player.getZ(), 4, 0.3, 0.5, 0.3, 0.01));
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}

		private void blast(ServerPlayer player) {
			player.level().explode(player, null, new ExplosionDamageCalculator() {
				@Override
				public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
					return entity != player;
				}
			}, player.position(), power, fire, Level.ExplosionInteraction.MOB);
		}
	}

	/**
	 * Everything living within {@code radius} takes {@code damage}, is pushed away by {@code knockback} and gets
	 * {@code target_effects}; the player gets {@code self_effects}. A radius of 0 touches only the player.
	 */
	private record Burst(double radius, float damage, double knockback, List<MobEffectInstance> targetEffects,
		List<MobEffectInstance> selfEffects, Optional<SimpleParticleType> particle, Optional<SoundEvent> sound) implements Action {
		private static final Codec<SimpleParticleType> PARTICLE = BuiltInRegistries.PARTICLE_TYPE.byNameCodec().comapFlatMap(
			type -> type instanceof SimpleParticleType simple ? DataResult.success(simple)
				: DataResult.error(() -> BuiltInRegistries.PARTICLE_TYPE.getKey(type) + " needs settings, pick a plain particle"),
			simple -> simple);
		static final MapCodec<Burst> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("radius", 0.0).forGetter(Burst::radius),
			ExtraCodecs.NON_NEGATIVE_FLOAT.optionalFieldOf("damage", 0.0F).forGetter(Burst::damage),
			Codec.DOUBLE.optionalFieldOf("knockback", 0.0).forGetter(Burst::knockback),
			EFFECTS.optionalFieldOf("target_effects", List.of()).forGetter(Burst::targetEffects),
			EFFECTS.optionalFieldOf("self_effects", List.of()).forGetter(Burst::selfEffects),
			PARTICLE.optionalFieldOf("particle").forGetter(Burst::particle),
			SOUND.optionalFieldOf("sound").forGetter(Burst::sound)
		).apply(i, Burst::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			if (radius > 0) {
				for (LivingEntity e : nearby(p, l, radius)) {
					for (MobEffectInstance effect : targetEffects) {
						e.addEffect(new MobEffectInstance(effect), p);
					}
					if (damage > 0) {
						e.hurtServer(l, p.damageSources().mobAttack(p), damage);
					}
					if (knockback > 0) {
						Vec3 away = e.position().subtract(p.position()).normalize();
						knock(e, away.x * knockback, 0.3, away.z * knockback);
					}
				}
			}
			for (MobEffectInstance effect : selfEffects) {
				p.addEffect(new MobEffectInstance(effect));
			}
			particle.ifPresent(type -> {
				double spread = Math.max(0.6, Math.min(radius * 0.4, 2.0));
				l.sendParticles(type, p.getX(), p.getY() + p.getBbHeight() / 2, p.getZ(), 40, spread, 0.6, spread, 0.05);
			});
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}
	}

	/** A guardian's beam: hits what the player is looking at within {@code range}, with optional effects. */
	private record Laser(float damage, double range, List<MobEffectInstance> effects, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Laser> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			ExtraCodecs.NON_NEGATIVE_FLOAT.optionalFieldOf("damage", 6.0F).forGetter(Laser::damage),
			Codec.DOUBLE.optionalFieldOf("range", 16.0).forGetter(Laser::range),
			EFFECTS.optionalFieldOf("effects", List.of()).forGetter(Laser::effects),
			SOUND.optionalFieldOf("sound").forGetter(Laser::sound)
		).apply(i, Laser::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			LivingEntity target = target(p, range);
			if (target == null) {
				return fail(p, "No target in sight");
			}
			Vec3 from = p.getEyePosition();
			Vec3 to = target.getEyePosition();
			for (int n = 0; n <= 16; n++) {
				Vec3 at = from.lerp(to, n / 16.0);
				l.sendParticles(ParticleTypes.BUBBLE, at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0);
			}
			target.hurtServer(l, l.damageSources().indirectMagic(p, p), damage);
			for (MobEffectInstance effect : effects) {
				target.addEffect(new MobEffectInstance(effect), p);
			}
			return play(p, l, sound.orElse(SoundEvents.GUARDIAN_ATTACK));
		}
	}

	/** Damage what's in reach and throw it into the air (Hoglin.throwTarget). Damage defaults to the mob's attack. */
	private record Toss(Optional<Float> damage, double reach, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Toss> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			ExtraCodecs.NON_NEGATIVE_FLOAT.optionalFieldOf("damage").forGetter(Toss::damage),
			Codec.DOUBLE.optionalFieldOf("reach", 4.0).forGetter(Toss::reach),
			SOUND.optionalFieldOf("sound").forGetter(Toss::sound)
		).apply(i, Toss::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			LivingEntity target = target(p, reach);
			if (target == null) {
				return fail(p, "Nothing in reach");
			}
			target.hurtServer(l, p.damageSources().mobAttack(p), damage.orElseGet(() -> attackDamage(p)));
			knock(target, 0, 0.8, 0);
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}
	}

	/** Drops an item at the player's feet, like a chicken laying an egg. */
	private record DropItem(Item item, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<DropItem> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(DropItem::item),
			SOUND.optionalFieldOf("sound").forGetter(DropItem::sound)
		).apply(i, DropItem::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			ItemEntity drop = new ItemEntity(l, p.getX(), p.getY(), p.getZ(), new ItemStack(item));
			drop.setDefaultPickUpDelay(); // laid on the ground, not straight into the pocket
			l.addFreshEntity(drop);
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}
	}

	/** Digs up a loot table from the block underfoot, if it's one of {@code blocks}, like a sniffer. */
	private record Dig(ResourceKey<LootTable> lootTable, TagKey<Block> blocks, Optional<SoundEvent> sound) implements Action {
		static final MapCodec<Dig> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			LootTable.KEY_CODEC.optionalFieldOf("loot_table", BuiltInLootTables.SNIFFER_DIGGING).forGetter(Dig::lootTable),
			TagKey.hashedCodec(Registries.BLOCK).optionalFieldOf("blocks", BlockTags.SNIFFER_DIGGABLE_BLOCK).forGetter(Dig::blocks),
			SOUND.optionalFieldOf("sound").forGetter(Dig::sound)
		).apply(i, Dig::new));

		@Override
		public boolean use(ServerPlayer p, ServerLevel l) {
			BlockPos below = p.blockPosition().below();
			if (!l.getBlockState(below).is(blocks)) {
				return fail(p, "Nothing to dig here");
			}
			p.dropFromGiftLootTable(l, lootTable, (level, stack) -> {
				ItemEntity found = new ItemEntity(level, p.getX(), p.getY() + 0.2, p.getZ(), stack);
				found.setDefaultPickUpDelay();
				level.addFreshEntity(found);
			});
			l.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, below, Block.getId(l.getBlockState(below)));
			sound.ifPresent(s -> play(p, l, s));
			return true;
		}
	}

	// --- power helpers ---

	private static boolean enderTeleport(ServerPlayer p, ServerLevel l, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(range));
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
			moved = p.randomTeleport(p.getX() + (p.getRandom().nextDouble() - 0.5) * range, p.getY() + p.getRandom().nextInt(16) - 8,
				p.getZ() + (p.getRandom().nextDouble() - 0.5) * range, true, BlockTags.ENDERMAN_DOES_NOT_TELEPORT_TO);
		}
		if (!moved) {
			return fail(p, "Nowhere to teleport");
		}
		p.resetFallDistance();
		l.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
		return play(p, l, SoundEvents.ENDERMAN_TELEPORT);
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
		play(p, l, SoundEvents.WARDEN_SONIC_BOOM);
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
			play(p, l, sound);
		}
		return true;
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

	private static boolean play(ServerPlayer p, ServerLevel l, SoundEvent sound) {
		l.playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
		return true;
	}

	private static boolean fail(ServerPlayer p, String message) {
		p.sendOverlayMessage(Component.literal(message));
		return false;
	}
}

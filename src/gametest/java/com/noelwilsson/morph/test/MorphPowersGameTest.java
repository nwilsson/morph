package com.noelwilsson.morph.test;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.client.MorphClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * Every power fires and does what it says; weaknesses bite; the blaze animates. Each check runs on the real server of a
 * real client. A cow 5 blocks south is the target dummy.
 */
public class MorphPowersGameTest implements FabricClientGameTest {
	private int failures;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("gamerule advance_weather false");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("difficulty normal");
			ctx.waitTicks(5);

			blazeAnimationAndKey(ctx, world);

			power(ctx, world, EntityTypes.GHAST, (p, l) -> count(l, p, EntityTypes.FIREBALL) == 1);
			power(ctx, world, EntityTypes.WITHER, (p, l) -> count(l, p, EntityTypes.WITHER_SKULL) == 1);
			power(ctx, world, EntityTypes.ENDER_DRAGON, (p, l) -> count(l, p, EntityTypes.DRAGON_FIREBALL) == 1);
			power(ctx, world, EntityTypes.SKELETON, (p, l) -> count(l, p, EntityTypes.ARROW) == 1);
			power(ctx, world, EntityTypes.STRAY, (p, l) -> count(l, p, EntityTypes.ARROW) == 1);
			power(ctx, world, EntityTypes.BOGGED, (p, l) -> count(l, p, EntityTypes.ARROW) == 1);
			power(ctx, world, EntityTypes.SNOW_GOLEM, (p, l) -> count(l, p, EntityTypes.SNOWBALL) >= 1); // 5-tick cooldown: the test's second press may fire too
			power(ctx, world, EntityTypes.WITCH, (p, l) -> count(l, p, EntityTypes.SPLASH_POTION) == 1);
			power(ctx, world, EntityTypes.EVOKER, (p, l) -> count(l, p, EntityTypes.EVOKER_FANGS) == 12);
			power(ctx, world, EntityTypes.BREEZE, (p, l) -> count(l, p, EntityTypes.WIND_CHARGE) == 1);
			power(ctx, world, EntityTypes.LLAMA, (p, l) -> count(l, p, EntityTypes.LLAMA_SPIT) == 1);
			power(ctx, world, EntityTypes.DROWNED, (p, l) -> count(l, p, EntityTypes.TRIDENT) == 1);
			power(ctx, world, EntityTypes.SHULKER, (p, l) -> count(l, p, EntityTypes.SHULKER_BULLET) == 1);
			power(ctx, world, EntityTypes.CHICKEN, (p, l) -> l.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3),
				e -> e.getItem().is(Items.EGG)).size() == 1);
			power(ctx, world, EntityTypes.ENDERMAN, (p, l) -> p.position().distanceTo(START) > 3);
			power(ctx, world, EntityTypes.WARDEN, (p, l) -> dummyHurt(l, p));
			power(ctx, world, EntityTypes.GUARDIAN, (p, l) -> dummyHurt(l, p));
			power(ctx, world, EntityTypes.ELDER_GUARDIAN, (p, l) -> dummy(l, p).hasEffect(MobEffects.MINING_FATIGUE));
			power(ctx, world, EntityTypes.IRON_GOLEM, (p, l) -> true, 4.0); // checked below with the dummy in reach
			power(ctx, world, EntityTypes.SQUID, (p, l) -> dummy(l, p).hasEffect(MobEffects.BLINDNESS), 3.0);
			power(ctx, world, EntityTypes.PUFFERFISH, (p, l) -> dummy(l, p).hasEffect(MobEffects.POISON), 2.0);
			power(ctx, world, EntityTypes.RAVAGER, (p, l) -> dummyHurt(l, p), 3.0);
			power(ctx, world, EntityTypes.GOAT, (p, l) -> dummyHurt(l, p), 2.0);
			power(ctx, world, EntityTypes.CAMEL, (p, l) -> p.position().distanceTo(START) > 1.5);
			power(ctx, world, EntityTypes.ARMADILLO, (p, l) -> p.hasEffect(MobEffects.RESISTANCE));
			power(ctx, world, EntityTypes.AXOLOTL, (p, l) -> p.hasEffect(MobEffects.REGENERATION));
			power(ctx, world, EntityTypes.FROG, (p, l) -> dummy(l, p).getDeltaMovement().z < -0.1 || dummy(l, p).position().z < START.z + 4.9);
			// Pounces and leaps carry the player toward the cow (south, +z).
			for (EntityType<?> pouncer : List.of(EntityTypes.WOLF, EntityTypes.CAT, EntityTypes.SPIDER, EntityTypes.FOX, EntityTypes.HORSE)) {
				power(ctx, world, pouncer, (p, l) -> p.getZ() > START.z + 1.0);
			}
			power(ctx, world, EntityTypes.TURTLE, (p, l) -> p.hasEffect(MobEffects.RESISTANCE));
			power(ctx, world, EntityTypes.PILLAGER, (p, l) -> count(l, p, EntityTypes.ARROW) == 1);
			power(ctx, world, EntityTypes.PIGLIN, (p, l) -> count(l, p, EntityTypes.ARROW) == 1);
			power(ctx, world, EntityTypes.ILLUSIONER, (p, l) -> p.hasEffect(MobEffects.INVISIBILITY) && dummy(l, p).hasEffect(MobEffects.BLINDNESS));
			power(ctx, world, EntityTypes.PHANTOM, (p, l) -> dummyHurt(l, p), 3.0);
			power(ctx, world, EntityTypes.VEX, (p, l) -> dummyHurt(l, p), 3.0);
			power(ctx, world, EntityTypes.POLAR_BEAR, (p, l) -> dummyHurt(l, p), 2.0);
			power(ctx, world, EntityTypes.HOGLIN, (p, l) -> dummyHurt(l, p), 3.0);
			power(ctx, world, EntityTypes.ZOGLIN, (p, l) -> dummyHurt(l, p), 3.0);
			power(ctx, world, EntityTypes.SHEEP, (p, l) -> l.getBlockState(BlockPos.containing(START.x, START.y - 1, START.z)).is(Blocks.DIRT));
			power(ctx, world, EntityTypes.SNIFFER, (p, l) -> !l.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3)).isEmpty());
			dolphin(ctx, world);
			creeper(ctx, world);
			weaknesses(ctx, world);
			melee(ctx, world);
			striderOnLava(ctx, world);
			relations(ctx, world);
		}
		if (failures > 0) {
			throw new AssertionError(failures + " morph power checks failed, see MORPH-TEST FAIL lines");
		}
		Morph.LOGGER.info("MORPH-TEST POWERS PASS");
	}

	private static final net.minecraft.world.phys.Vec3 START = new net.minecraft.world.phys.Vec3(0.5, -60, 0.5);

	/** Fresh stage: clear entities, stand at START looking south, a no-AI cow `distance` blocks ahead. */
	private void stage(ClientGameTestContext ctx, TestSingleplayerContext world, EntityType<?> morph, double distance) {
		// discard, not /kill: killed mobs linger through their death animation and drop loot.
		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(64), e -> true)) {
				e.discard();
			}
		});
		world.getServer().runCommand("fill -8 -61 -8 8 -61 16 minecraft:grass_block");
		world.getServer().runCommand("fill -8 -60 -8 8 -50 16 minecraft:air");
		ctx.waitTicks(2);
		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			ServerLevel l = p.level();
			MorphState.unmorph(p);
			p.removeAllEffects();
			p.clearFire();
			p.setHealth(p.getMaxHealth());
			p.teleportTo(START.x, START.y, START.z);
			p.setDeltaMovement(0, 0, 0);
			// The client owns its motion: without this, a dash from the last stage carries on after the teleport.
			p.connection.send(new ClientboundSetEntityMotionPacket(p));
			MorphState.unlock(p, morph);
			MorphState.morph(p, morph);
			Mob cow = EntityTypes.COW.create(l, EntitySpawnReason.COMMAND);
			cow.setNoAi(true);
			cow.setPos(START.x, START.y, START.z + distance);
			l.addFreshEntity(cow);
		});
		// Aim only once the client has the morph's eye height, or the look-at is computed from the wrong eye.
		ctx.waitTicks(3);
		world.getConnection().waitForClientboundPackets();
		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			p.lookAt(EntityAnchorArgument.Anchor.EYES, dummy(p.level(), p).getBoundingBox().getCenter());
		});
		ctx.waitTicks(3);
	}

	private void power(ClientGameTestContext ctx, TestSingleplayerContext world, EntityType<?> morph, BiPredicate<ServerPlayer, ServerLevel> fired) {
		power(ctx, world, morph, fired, 5.0);
	}

	private void power(ClientGameTestContext ctx, TestSingleplayerContext world, EntityType<?> morph, BiPredicate<ServerPlayer, ServerLevel> fired,
		double distance) {
		stage(ctx, world, morph, distance);
		Morph.LOGGER.info("MORPH-TEST debug {} before: {}", morph.toShortString(), world.getServer().computeOnServer(s -> around(world)));
		SPAWNED.clear();
		world.getServer().runOnServer(server -> MorphPowers.use(world.getConnection().getServerPlayer()));
		ctx.waitTicks(5);
		// A second press while recharging must do nothing.
		int before = SPAWNED.size();
		world.getServer().runOnServer(server -> MorphPowers.use(world.getConnection().getServerPlayer()));
		if (morph != EntityTypes.SNOW_GOLEM) {
			check(SPAWNED.size() == before, morph.toShortString() + ": fired again during cooldown");
		}
		if (morph == EntityTypes.WARDEN) {
			ctx.waitTicks(35); // the boom lands partway through the wind-up
		}
		Morph.LOGGER.info("MORPH-TEST debug {} after: {}", morph.toShortString(), world.getServer().computeOnServer(s -> around(world)));
		String name = morph.toShortString();
		boolean ok = world.getServer().computeOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			return fired.test(p, p.level());
		});
		check(ok, name + ": power had no effect");
		boolean onCooldown = world.getServer().computeOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			return p.getAttachedOrElse(MorphPowers.READY_AT, 0L) > 0;
		});
		check(onCooldown, name + ": no cooldown after use");
		if (morph == EntityTypes.IRON_GOLEM) {
			check(world.getServer().computeOnServer(s -> dummyHurt(world.getConnection().getServerPlayer().level(),
				world.getConnection().getServerPlayer())), "iron_golem: toss didn't hurt the target");
		}
		if (ok) {
			Morph.LOGGER.info("MORPH-TEST ok {}", name);
		}
	}

	/** The blaze: animation age advances, R (the real key, over the network) shoots three fireballs. */
	private void blazeAnimationAndKey(ClientGameTestContext ctx, TestSingleplayerContext world) {
		stage(ctx, world, EntityTypes.BLAZE, 5.0);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		ctx.waitTicks(5);
		int age1 = ctx.computeOnClient(mc -> MorphClient.disguise(mc.player).tickCount);
		ctx.waitTicks(10);
		int age2 = ctx.computeOnClient(mc -> MorphClient.disguise(mc.player).tickCount);
		check(age2 - age1 >= 9, "blaze disguise age didn't advance (" + age1 + " -> " + age2 + "), rods won't spin");

		SPAWNED.clear();
		ctx.getInput().pressKey(MorphClient.USE_POWER);
		ctx.waitTicks(4);
		world.getConnection().waitForServerboundPackets();
		check(world.getServer().computeOnServer(s -> count(world.getConnection().getServerPlayer().level(), world.getConnection().getServerPlayer(),
			EntityTypes.SMALL_FIREBALL)) >= 1, "blaze: R key didn't shoot fireballs");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("blaze-fireballs"));
		ctx.waitTicks(3);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("blaze-hud-cooldown"));
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
	}

	/** The dolphin dashes in water and refuses on land. */
	private void dolphin(ClientGameTestContext ctx, TestSingleplayerContext world) {
		stage(ctx, world, EntityTypes.DOLPHIN, 8.0);
		world.getServer().runOnServer(server -> MorphPowers.use(world.getConnection().getServerPlayer()));
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().getAttachedOrElse(MorphPowers.READY_AT, 0L) == 0L),
			"dolphin: dashed on land");
		world.getServer().runCommand("fill -2 -60 -2 2 -56 12 minecraft:water");
		ctx.waitTicks(10);
		world.getServer().runOnServer(server -> MorphPowers.use(world.getConnection().getServerPlayer()));
		ctx.waitTicks(5);
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().getZ() > START.z + 1.5),
			"dolphin: no dash in water (z=" + world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().getZ()) + ")");
		// The water spreads up to 7 blocks past the pool, outside what stage() clears.
		world.getServer().runCommand("fill -12 -60 -12 12 -55 24 minecraft:air");
		Morph.LOGGER.info("MORPH-TEST ok dolphin");
	}

	private void creeper(ClientGameTestContext ctx, TestSingleplayerContext world) {
		stage(ctx, world, EntityTypes.CREEPER, 3.0);
		world.getServer().runOnServer(server -> MorphPowers.use(world.getConnection().getServerPlayer()));
		ctx.waitTicks(40);
		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			check(p.isAlive() && p.getHealth() == p.getMaxHealth(), "creeper: player hurt by own blast (" + p.getHealth() + ")");
			check(p.level().getBlockState(BlockPos.containing(START.x, START.y - 1, START.z)).isAir(), "creeper: no crater (" + p.level().getBlockState(BlockPos.containing(START.x, START.y - 1, START.z)) + ", at feet " + p.level().getBlockState(BlockPos.containing(START)) + ")");
			check(dummy(p.level(), p) == null || dummy(p.level(), p).getHealth() < dummy(p.level(), p).getMaxHealth(), "creeper: cow untouched");
		});
		Morph.LOGGER.info("MORPH-TEST ok creeper");
	}

	private void weaknesses(ClientGameTestContext ctx, TestSingleplayerContext world) {
		// Blaze in water: vanilla's own water damage, once the template says blazes are sensitive.
		stage(ctx, world, EntityTypes.BLAZE, 8.0);
		world.getServer().runCommand("fill -1 -60 -1 1 -58 1 minecraft:water");
		ctx.waitTicks(30);
		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			check(p.getHealth() < p.getMaxHealth(), "blaze: no damage in water (" + p.getHealth() + "/" + p.getMaxHealth() + ")");
		});
		Morph.LOGGER.info("MORPH-TEST ok blaze-water");

		// Zombie at noon under open sky burns, like a zombie.
		stage(ctx, world, EntityTypes.ZOMBIE, 8.0);

		boolean burned = false;
		for (int i = 0; i < 20 && !burned; i++) {
			ctx.waitTicks(10);
			burned = world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().isOnFire());
		}
		check(burned, "zombie: didn't burn in daylight");
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().isInvertedHealAndHarm()),
			"zombie: healing isn't inverted");
		Morph.LOGGER.info("MORPH-TEST ok zombie-sun");

		// Fish on land run out of air.
		stage(ctx, world, EntityTypes.COD, 8.0);
		ctx.waitTicks(40);
		world.getServer().runOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			check(p.getAirSupply() < p.getMaxAirSupply() - 20, "cod: air didn't drop on land (" + p.getAirSupply() + ")");
		});
		Morph.LOGGER.info("MORPH-TEST ok cod-dry");

	}

	private void melee(ClientGameTestContext ctx, TestSingleplayerContext world) {
		stage(ctx, world, EntityTypes.BEE, 1.5);
		world.getServer().runOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			p.attack(dummy(p.level(), p));
		});
		ctx.waitTicks(2);
		check(world.getServer().computeOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			return dummy(p.level(), p).hasEffect(MobEffects.POISON);
		}), "bee: sting didn't poison");
		Morph.LOGGER.info("MORPH-TEST ok bee-sting");

		stage(ctx, world, EntityTypes.ZOMBIE, 1.5);
		world.getServer().runOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			check(p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) == 3.0,
				"zombie: attack damage " + p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) + ", want 3");
		});
	}

	/** Walk on a real lava pool as a strider, with the real movement key; sink in and float back up. */
	private void striderOnLava(ClientGameTestContext ctx, TestSingleplayerContext world) {
		stage(ctx, world, EntityTypes.STRIDER, 12.0);
		world.getServer().runCommand("fill -4 -63 -4 4 -61 8 minecraft:lava");
		ctx.waitTicks(40);
		double y = ctx.computeOnClient(mc -> mc.player.getY());
		check(y > -60.6 && y < -60.4, "strider: not standing on the lava surface, y=" + y);
		double z0 = ctx.computeOnClient(mc -> mc.player.getZ());
		ctx.getInput().holdKeyFor(options -> options.keyUp, 20);
		ctx.waitTicks(5);
		double z1 = ctx.computeOnClient(mc -> mc.player.getZ());
		double y1 = ctx.computeOnClient(mc -> mc.player.getY());
		check(Math.abs(z1 - z0) > 2 && y1 > -60.6, "strider: couldn't walk across lava (moved " + (z1 - z0) + ", y=" + y1 + ")");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("strider-on-lava"));

		// Dropped deep into lava, it bobs back up like a strider.
		world.getServer().runOnServer(s -> world.getConnection().getServerPlayer().teleportTo(0.5, -62.9, 0.5));
		ctx.waitTicks(80);
		double y2 = ctx.computeOnClient(mc -> mc.player.getY());
		check(y2 > -60.7, "strider: didn't float up out of lava, y=" + y2);
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().getHealth()
			== world.getConnection().getServerPlayer().getMaxHealth()), "strider: hurt by lava");

		// Unmorphed, lava is lava again.
		world.getServer().runOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			MorphState.unmorph(p);
			p.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
			p.teleportTo(0.5, -60.4, 0.5);
		});
		ctx.waitTicks(30);
		double y3 = ctx.computeOnClient(mc -> mc.player.getY());
		check(y3 < -60.7, "unmorphed: still standing on lava, y=" + y3);
		Morph.LOGGER.info("MORPH-TEST ok strider-lava");
		world.getServer().runCommand("fill -4 -63 -4 4 -62 8 minecraft:dirt");
		world.getServer().runCommand("fill -4 -61 -4 4 -61 8 minecraft:grass_block");
	}

	/** Monsters ignore monster morphs until hit; village golems hunt them; creepers flee cats. */
	private void relations(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runCommand("time set midnight"); // undead mustn't burn mid-test

		// Control: an unmorphed player is attacked.
		stage(ctx, world, EntityTypes.PIG, 15.0);
		world.getServer().runOnServer(s -> MorphState.unmorph(world.getConnection().getServerPlayer()));
		spawn(world, EntityTypes.ZOMBIE, 4);
		ctx.waitTicks(40);
		check(targetOf(world, EntityTypes.ZOMBIE) == Target.PLAYER, "control: zombie didn't target an unmorphed player");

		// As a zombie: ignored.
		stage(ctx, world, EntityTypes.ZOMBIE, 15.0);
		spawn(world, EntityTypes.ZOMBIE, 4);
		spawn(world, EntityTypes.SKELETON, -4);
		ctx.waitTicks(60);
		check(targetOf(world, EntityTypes.ZOMBIE) != Target.PLAYER, "zombie morph: zombie still targets the player");
		check(targetOf(world, EntityTypes.SKELETON) != Target.PLAYER, "zombie morph: skeleton still targets the player");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("zombie-among-monsters"));

		// Hit it, and it fights back.
		world.getServer().runOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			p.attack(p.level().getEntities(EntityTypes.ZOMBIE, p.getBoundingBox().inflate(16), e -> true).getFirst());
		});
		ctx.waitTicks(20);
		check(targetOf(world, EntityTypes.ZOMBIE) == Target.PLAYER, "zombie morph: provoked zombie didn't fight back");
		Morph.LOGGER.info("MORPH-TEST ok monsters-ignore");

		// Village iron golem hunts a zombie-morphed player.
		stage(ctx, world, EntityTypes.ZOMBIE, 15.0);
		spawn(world, EntityTypes.IRON_GOLEM, 6);
		ctx.waitTicks(60);
		check(targetOf(world, EntityTypes.IRON_GOLEM) == Target.PLAYER, "zombie morph: iron golem ignores the player");
		Morph.LOGGER.info("MORPH-TEST ok golem-hunts");

		// Creepers back away from a cat.
		stage(ctx, world, EntityTypes.CAT, 15.0);
		spawn(world, EntityTypes.CREEPER, 3);
		ctx.waitTicks(5);
		double before = world.getServer().computeOnServer(s -> distanceTo(world, EntityTypes.CREEPER));
		ctx.waitTicks(60);
		double after = world.getServer().computeOnServer(s -> distanceTo(world, EntityTypes.CREEPER));
		check(after > before + 1.5, "cat morph: creeper didn't flee (" + before + " -> " + after + ")");
		Morph.LOGGER.info("MORPH-TEST ok creeper-flees-cat");
		world.getServer().runCommand("time set noon");
	}

	private enum Target { NONE, PLAYER, OTHER }

	private static void spawn(TestSingleplayerContext world, EntityType<? extends Mob> type, double dz) {
		world.getServer().runOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			Mob mob = type.create(p.level(), EntitySpawnReason.COMMAND);
			mob.setPos(START.x, START.y, START.z + dz);
			mob.setPersistenceRequired();
			p.level().addFreshEntity(mob);
		});
	}

	private static Target targetOf(TestSingleplayerContext world, EntityType<? extends Mob> type) {
		return world.getServer().computeOnServer(s -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			Mob mob = p.level().getEntities(type, p.getBoundingBox().inflate(20), e -> true).getFirst();
			LivingEntity target = mob.getTarget();
			return target == null ? Target.NONE : target == p ? Target.PLAYER : Target.OTHER;
		});
	}

	private static double distanceTo(TestSingleplayerContext world, EntityType<?> type) {
		ServerPlayer p = world.getConnection().getServerPlayer();
		return p.level().getEntities(type, p.getBoundingBox().inflate(30), e -> true).getFirst().distanceTo(p);
	}

	private static String around(TestSingleplayerContext world) {
		ServerPlayer p = world.getConnection().getServerPlayer();
		StringBuilder out = new StringBuilder("player " + p.position() + " rot " + p.getYRot() + "/" + p.getXRot() + " morph " + MorphState.current(p) + " |");
		for (var e : p.level().getEntities(p, p.getBoundingBox().inflate(40), e -> true)) {
			out.append(' ').append(e.getType().toShortString()).append('@').append(e.blockPosition().toShortString());
		}
		return out.toString();
	}

	/** Entities that joined the world since the power was used: projectiles can hit and vanish before we look. */
	private static final List<EntityType<?>> SPAWNED = new CopyOnWriteArrayList<>();

	static {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> SPAWNED.add(entity.getType()));
	}

	private static int count(ServerLevel l, ServerPlayer p, EntityType<?> type) {
		return (int) SPAWNED.stream().filter(t -> t == type).count();
	}

	private static LivingEntity dummy(ServerLevel l, ServerPlayer p) {
		return l.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(20), e -> e.getType() == EntityTypes.COW)
			.stream().findFirst().orElse(null);
	}

	private static boolean dummyHurt(ServerLevel l, ServerPlayer p) {
		LivingEntity cow = dummy(l, p);
		return cow == null || cow.getHealth() < cow.getMaxHealth();
	}

	private void check(boolean condition, String message) {
		if (!condition) {
			failures++;
			Morph.LOGGER.error("MORPH-TEST FAIL: {}", message);
		}
	}

}

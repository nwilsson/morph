package com.noelwilsson.morph.test;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphBody;
import com.noelwilsson.morph.MorphLimits;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphRelations;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import com.noelwilsson.morph.mixin.EntityInvoker;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.phys.Vec3;

/** Phase 4, living in the body: immunities, how mobs react, what can be done to a body, riding, and body limits. */
public class MorphBodyGameTest implements FabricClientGameTest {
	private static final Vec3 START = new Vec3(0.5, -60, 0.5);
	private int failures;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("difficulty normal");
			ctx.waitTicks(5);
			immunities(world);
			villagers(ctx, world);
			reactions(world);
			lightning(world);
			sheep(world);
			milk(world);
			riding(ctx, world);
			horseLeap(ctx, world);
			limits(world);
		}
		if (failures > 0) {
			throw new AssertionError(failures + " body checks failed, see MORPH-TEST FAIL lines");
		}
		Morph.LOGGER.info("MORPH-TEST BODY PASS");
	}

	private static ServerPlayer player(TestSingleplayerContext world) {
		return world.getConnection().getServerPlayer();
	}

	/** Back to normal, standing at START, then this morph. */
	private static ServerPlayer become(TestSingleplayerContext world, EntityType<?> type) {
		ServerPlayer p = player(world);
		MorphState.unmorph(p);
		p.removeAllEffects();
		p.getInventory().clearContent();
		p.teleportTo(START.x, START.y, START.z);
		if (type != null) {
			MorphState.unlock(p, type);
			MorphState.morph(p, type);
		}
		return p;
	}

	private void immunities(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, null);
			p.addEffect(new MobEffectInstance(MobEffects.POISON, 200));
			MorphState.unlock(p, EntityTypes.SPIDER);
			MorphState.morph(p, EntityTypes.SPIDER);
			check(!p.hasEffect(MobEffects.POISON), "spider: becoming one didn't cure poison");
			p.addEffect(new MobEffectInstance(MobEffects.POISON, 200));
			check(!p.hasEffect(MobEffects.POISON), "spider: poisoned");

			// Cobwebs slow players to a quarter; a spider walks through.
			EntityInvoker stuck = (EntityInvoker) p;
			stuck.morph$setStuckSpeedMultiplier(Vec3.ZERO);
			p.makeStuckInBlock(Blocks.COBWEB.defaultBlockState(), new Vec3(0.25, 0.05, 0.25));
			check(stuck.morph$stuckSpeedMultiplier().equals(Vec3.ZERO), "spider: stuck in a cobweb");
			become(world, EntityTypes.COW);
			p.makeStuckInBlock(Blocks.COBWEB.defaultBlockState(), new Vec3(0.25, 0.05, 0.25));
			check(!stuck.morph$stuckSpeedMultiplier().equals(Vec3.ZERO), "cow: walked through a cobweb");
			stuck.morph$setStuckSpeedMultiplier(Vec3.ZERO);

			become(world, EntityTypes.WITHER_SKELETON);
			p.addEffect(new MobEffectInstance(MobEffects.WITHER, 200));
			check(!p.hasEffect(MobEffects.WITHER), "wither skeleton: withered");
			become(world, EntityTypes.ZOMBIE);
			p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200));
			check(!p.hasEffect(MobEffects.REGENERATION), "zombie: regenerating");
			become(world, EntityTypes.COW);
			p.addEffect(new MobEffectInstance(MobEffects.POISON, 200));
			check(p.hasEffect(MobEffects.POISON), "cow: immune to poison");

			become(world, EntityTypes.STRAY);
			check(!p.canFreeze(), "stray: freezes");
			become(world, EntityTypes.COW);
			check(p.canFreeze(), "cow: can't freeze");
			check(!PowderSnowBlock.canEntityWalkOnPowderSnow(p), "cow: walks on powder snow");
			become(world, EntityTypes.RABBIT);
			check(PowderSnowBlock.canEntityWalkOnPowderSnow(p), "rabbit: sinks in powder snow");
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok immunities");
	}

	/** A villager notices a zombie-morphed player as a threat (its brain's nearest hostile) and won't trade. */
	private void villagers(ClientGameTestContext ctx, TestSingleplayerContext world) {
		java.util.UUID[] id = new java.util.UUID[1];
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.ZOMBIE);
			check(MorphRelations.villagersFear(p) != null, "zombie: villagers don't fear it");
			Villager villager = EntityTypes.VILLAGER.create(p.level(), EntitySpawnReason.COMMAND);
			villager.setPos(START.x + 3, START.y, START.z);
			p.level().addFreshEntity(villager);
			id[0] = villager.getUUID();
		});
		// The sensor runs now and then, and the villager forgets the threat once it has run far enough: watch for it.
		boolean seen = false;
		for (int tick = 0; tick < 100 && !seen; tick++) {
			ctx.waitTicks(1);
			seen = world.getServer().computeOnServer(server -> {
				ServerPlayer p = player(world);
				return p.level().getEntity(id[0]) instanceof Villager villager
					&& villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE).filter(e -> e == p).isPresent();
			});
		}
		check(seen, "villager: never saw the zombie-morphed player as a threat");
		world.getServer().runOnServer(server -> {
			ServerPlayer p = player(world);
			if (p.level().getEntity(id[0]) instanceof Villager villager) {
				villager.discard();
			}
			become(world, EntityTypes.COW);
			check(MorphRelations.villagersFear(p) == null, "cow: villagers fear it");
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok villagers");
	}

	private void reactions(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.ENDERMAN);
			check(MorphRelations.isFellowEnderman(p), "enderman: endermen mind its stare");
			ServerLevel l = p.level();
			Mob phantom = EntityTypes.PHANTOM.create(l, EntitySpawnReason.COMMAND);
			phantom.setPos(START.x, START.y + 5, START.z);
			l.addFreshEntity(phantom);
			check(!MorphRelations.catPlayerNear(phantom), "enderman: scares phantoms");
			become(world, EntityTypes.CAT);
			check(MorphRelations.catPlayerNear(phantom), "cat: doesn't scare phantoms");
			phantom.discard();

			Mob piglin = EntityTypes.PIGLIN.create(l, EntitySpawnReason.COMMAND);
			piglin.setPos(START.x + 3, START.y, START.z);
			l.addFreshEntity(piglin);
			become(world, EntityTypes.COW);
			check(MorphRelations.monsterIgnores(piglin, p), "cow: piglins hunt it");
			become(world, EntityTypes.WITHER_SKELETON);
			check(!MorphRelations.monsterIgnores(piglin, p), "wither skeleton: piglins leave their nemesis alone");
			piglin.discard();
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok reactions");
	}

	private void lightning(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.CREEPER);
			LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(p.level(), EntitySpawnReason.COMMAND);
			bolt.setPos(START);
			p.thunderHit(p.level(), bolt);
			MorphVariant now = MorphState.currentVariant(p);
			check(now != null && now.data().getBooleanOr("powered", false), "creeper: lightning didn't charge it (" + now + ")");
			CompoundTag charged = new CompoundTag();
			charged.putBoolean("powered", true);
			check(MorphState.isUnlocked(p, MorphVariant.parse(EntityTypes.CREEPER, charged, p.level())), "creeper: charged look not unlocked");
			p.clearFire();
			p.setHealth(p.getMaxHealth());
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok charged-creeper");
	}

	/** Shear yourself as a sheep (sneak and use shears), then eat grass to grow the wool back. */
	private void sheep(TestSingleplayerContext world) {
		world.getServer().runCommand("fill -2 -61 -2 2 -61 2 minecraft:grass_block");
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.SHEEP);
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
			p.setShiftKeyDown(true);
			UseItemCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND);
			p.setShiftKeyDown(false);
			MorphVariant now = MorphState.currentVariant(p);
			check(now != null && now.data().getBooleanOr("Sheared", false), "sheep: not sheared (" + now + ")");
			List<ItemEntity> wool = p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3), e -> e.getItem().is(net.minecraft.tags.ItemTags.WOOL));
			check(!wool.isEmpty(), "sheep: no wool dropped");
			check(p.getMainHandItem().getDamageValue() == 1, "sheep: shears not worn (" + p.getMainHandItem().getDamageValue() + ")");
			wool.forEach(Entity::discard);

			MorphPowers.clearCooldowns(p);
			MorphPowers.use(p);
			now = MorphState.currentVariant(p);
			check(now != null && !now.data().getBooleanOr("Sheared", false), "sheep: eating grass didn't grow the wool back (" + now + ")");
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok sheep");
	}

	/** Another player milks you as a cow. */
	private void milk(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.COW);
			FakePlayer farmer = FakePlayer.get(p.level());
			farmer.setPos(START.x + 1, START.y, START.z);
			farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
			UseEntityCallback.EVENT.invoker().interact(farmer, p.level(), InteractionHand.MAIN_HAND, p, null);
			check(farmer.getMainHandItem().is(Items.MILK_BUCKET), "cow: milking gave " + farmer.getMainHandItem());
			farmer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok milk");
	}

	/** Another player saddles you as a horse and gets on; changing body throws them off and drops the saddle. */
	private void riding(ClientGameTestContext ctx, TestSingleplayerContext world) {
		int[] zombieId = new int[1];
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.HORSE);
			ServerLevel l = p.level();
			FakePlayer rider = FakePlayer.get(l);
			rider.setPos(START.x + 1, START.y, START.z);
			rider.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			UseEntityCallback.EVENT.invoker().interact(rider, l, InteractionHand.MAIN_HAND, p, null);
			check(!rider.isPassenger(), "horse: ridden without a saddle");

			rider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
			UseEntityCallback.EVENT.invoker().interact(rider, l, InteractionHand.MAIN_HAND, p, null);
			check(MorphBody.tack(p).is(Items.SADDLE) && rider.getMainHandItem().isEmpty(), "horse: saddle not put on");
			check(UseEntityCallback.EVENT.invoker().interact(rider, l, InteractionHand.MAIN_HAND, p, null).consumesAction(),
				"horse: getting on wasn't accepted");
			// Fabric's fake players never ride anything, so a zombie stands in for the rider from here.
			Mob zombie = EntityTypes.ZOMBIE.create(l, EntitySpawnReason.COMMAND);
			zombie.setPos(START.x + 1, START.y, START.z);
			l.addFreshEntity(zombie);
			check(zombie.startRiding(p), "horse: couldn't get on");
			p.positionRider(zombie);
			double seat = zombie.getY() - p.getY();
			// The same rider on a real horse sits at the same height.
			Mob horse = EntityTypes.HORSE.create(l, EntitySpawnReason.COMMAND);
			Mob control = EntityTypes.ZOMBIE.create(l, EntitySpawnReason.COMMAND);
			horse.setPos(START.x + 4, START.y, START.z);
			control.setPos(START.x + 4, START.y, START.z);
			l.addFreshEntity(horse);
			l.addFreshEntity(control);
			control.startRiding(horse, true, false);
			horse.positionRider(control);
			double real = control.getY() - horse.getY();
			check(Math.abs(seat - real) < 0.01, "horse: rider sits at " + seat + " above the morph, " + real + " above a horse");
			horse.discard();
			control.discard();
			zombieId[0] = zombie.getId();
		});
		// The ridden player's own game has to know it carries the rider, or draws them frozen where they got on.
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		check(ctx.computeOnClient(mc -> mc.player.getPassengers().stream().anyMatch(e -> e.getId() == zombieId[0])),
			"horse: the ridden player's client doesn't see its rider");
		world.getServer().runOnServer(server -> {
			ServerPlayer p = player(world);
			ServerLevel l = p.level();
			FakePlayer rider = FakePlayer.get(l);
			Entity zombie = l.getEntity(zombieId[0]);

			MorphState.unlock(p, EntityTypes.COW);
			MorphState.morph(p, EntityTypes.COW);
			check(!zombie.isPassenger(), "cow: still carrying a rider");
			zombie.discard();
			check(MorphBody.tack(p).isEmpty(), "cow: still wearing the saddle");
			check(!l.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3), e -> e.getItem().is(Items.SADDLE)).isEmpty(),
				"cow: the saddle didn't drop");
			l.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3)).forEach(Entity::discard);

			// A pig takes a saddle too, and shears take it off again.
			become(world, EntityTypes.PIG);
			rider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
			UseEntityCallback.EVENT.invoker().interact(rider, l, InteractionHand.MAIN_HAND, p, null);
			check(MorphBody.tack(p).is(Items.SADDLE), "pig: saddle not put on");
			rider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
			UseEntityCallback.EVENT.invoker().interact(rider, l, InteractionHand.MAIN_HAND, p, null);
			check(MorphBody.tack(p).isEmpty(), "pig: shears didn't take the saddle off");
			l.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3)).forEach(Entity::discard);
			rider.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok riding");
	}

	/** A horse's leap goes higher than a player can fall unhurt, but a horse can fall 6 blocks and lands it safely. */
	private void horseLeap(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.HORSE);
			p.setHealth(p.getMaxHealth());
			check(p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) == 6.0, "horse: safe fall distance " + p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE));
			check(p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER) == 0.5, "horse: fall damage multiplier " + p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
			MorphPowers.clearCooldowns(p);
			MorphPowers.use(p);
		});
		double[] highest = {START.y};
		for (int tick = 0; tick < 60; tick++) {
			ctx.waitTicks(1);
			highest[0] = Math.max(highest[0], world.getServer().computeOnServer(server -> player(world).getY()));
		}
		world.getServer().runOnServer(server -> {
			ServerPlayer p = player(world);
			check(highest[0] - START.y > 3.0, "horse: the leap only rose " + (highest[0] - START.y) + " blocks, not past a player's safe fall");
			check(p.getHealth() == p.getMaxHealth(), "horse: hurt landing its own leap (" + p.getHealth() + "/" + p.getMaxHealth() + ")");
			become(world, EntityTypes.COW);
			check(p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) == 3.0, "cow: kept the horse's safe fall distance");
			check(p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER) == 1.0, "cow: kept the horse's fall damage multiplier");
			become(world, null);
		});
		Morph.LOGGER.info("MORPH-TEST ok horse-leap");
	}

	private void limits(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = become(world, EntityTypes.COW);
			check(MorphLimits.refusal(p, new ItemStack(Items.DIRT)) == null, "body_limits off: cow refused dirt");
		});
		world.getServer().runCommand("gamerule morph:body_limits true");
		world.getServer().runOnServer(server -> {
			ServerPlayer p = player(world);
			check(MorphLimits.refusal(p, new ItemStack(Items.DIRT)) != null, "cow: can place dirt");
			check(MorphLimits.refusal(p, new ItemStack(Items.BREAD)) == null, "cow: can't eat bread");
			check(MorphLimits.refusal(p, new ItemStack(Items.COOKED_BEEF)) != null, "cow: eats beef");
			check(MorphLimits.refusal(p, new ItemStack(Items.MILK_BUCKET)) == null, "cow: can't drink milk");
			p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			MorphLimits.tick(p);
			check(p.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && p.getInventory().contains(new ItemStack(Items.IRON_HELMET)),
				"cow: kept wearing a helmet");

			become(world, EntityTypes.WOLF);
			check(MorphLimits.refusal(p, new ItemStack(Items.COOKED_BEEF)) == null, "wolf: can't eat beef");
			check(MorphLimits.refusal(p, new ItemStack(Items.BREAD)) != null, "wolf: eats bread");
			become(world, EntityTypes.ZOMBIE);
			check(MorphLimits.refusal(p, new ItemStack(Items.DIRT)) == null, "zombie: can't place dirt");
			p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			MorphLimits.tick(p);
			check(!p.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "zombie: helmet taken off");
			p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			become(world, null);
		});
		world.getServer().runCommand("gamerule morph:body_limits false");
		Morph.LOGGER.info("MORPH-TEST ok limits");
	}

	private void check(boolean condition, String message) {
		if (!condition) {
			failures++;
			Morph.LOGGER.error("MORPH-TEST FAIL: {}", message);
		}
	}
}

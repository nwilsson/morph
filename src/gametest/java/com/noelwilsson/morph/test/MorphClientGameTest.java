package com.noelwilsson.morph.test;

import com.mojang.blaze3d.platform.InputConstants;
import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphAbilities;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphProgress;
import com.noelwilsson.morph.MorphRules;
import com.noelwilsson.morph.MorphSounds;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import com.noelwilsson.morph.client.MorphClient;
import com.noelwilsson.morph.client.MorphRadialScreen;
import com.noelwilsson.morph.client.MorphSidebarScreen;
import com.noelwilsson.morph.client.MorphUnlockToast;
import com.noelwilsson.morph.mixin.EntityInvoker;
import com.noelwilsson.morph.mixin.LivingEntityInvoker;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * End to end in a real client: kill a parrot, morph into it, check health/flight/hitbox on both sides,
 * screenshot it, unmorph, check everything is back.
 */
public class MorphClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("gamerule advance_time false");
			ctx.waitTicks(5);

			// Locked: morphing before a kill must do nothing.
			command(world, "morph minecraft:parrot");
			ctx.waitTicks(2);
			check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == null,
				"morphed into a parrot without unlocking it");

			// Kill a parrot as the player.
			world.getServer().runOnServer(server -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				ServerLevel level = player.level();
				Mob parrot = EntityTypes.PARROT.create(level, EntitySpawnReason.COMMAND);
				parrot.setPos(player.getX() + 2, player.getY(), player.getZ());
				level.addFreshEntity(parrot);
				parrot.hurtServer(level, player.damageSources().playerAttack(player), 1000);
			});
			ctx.waitTicks(2);
			check(world.getServer().computeOnServer(s -> MorphState.isUnlocked(world.getConnection().getServerPlayer(), EntityTypes.PARROT)),
				"killing a parrot didn't unlock it");

			command(world, "morph parrot");
			ctx.waitTicks(25); // one ability refresh
			world.getConnection().waitForClientboundPackets();

			world.getServer().runOnServer(server -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				check(MorphState.current(player) == EntityTypes.PARROT, "server: not a parrot");
				check(player.getMaxHealth() == 6.0F, "server: max health " + player.getMaxHealth() + ", want 6");
				check(player.getHealth() == 6.0F, "server: health " + player.getHealth());
				check(player.getAbilities().mayfly, "server: parrot can't fly");
				checkHitbox("server", player.getBbWidth(), player.getBbHeight(), EntityTypes.PARROT);
			});
			ctx.runOnClient(mc -> {
				check(MorphState.current(mc.player) == EntityTypes.PARROT, "client: morph not synced");
				check(mc.player.getAbilities().mayfly, "client: flight not synced");
				checkHitbox("client", mc.player.getBbWidth(), mc.player.getBbHeight(), EntityTypes.PARROT);
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			});
			ctx.waitTicks(10);
			Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-parrot"));

			command(world, "unmorph");
			ctx.waitTicks(25);
			world.getConnection().waitForClientboundPackets();
			world.getServer().runOnServer(server -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				check(MorphState.current(player) == null, "server: still morphed");
				check(player.getMaxHealth() == 20.0F, "server: max health " + player.getMaxHealth() + " after unmorph");
				check(!player.getAbilities().mayfly, "server: can still fly after unmorph");
				checkHitbox("server", player.getBbWidth(), player.getBbHeight(), EntityTypes.PLAYER);
			});
			ctx.runOnClient(mc -> checkHitbox("client", mc.player.getBbWidth(), mc.player.getBbHeight(), EntityTypes.PLAYER));
			Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-unmorphed"));

			health(world);
			variants(ctx, world);
			animations(ctx, world);
			relations(world);
			cooldownSurvivesMorphing(world);
			squid(ctx, world);
			nautilus(ctx, world);
			phantom(ctx, world);
			heldItems(ctx, world);
			sidebar(ctx, world);
			search(ctx, world);
			favorites(ctx, world);
			toggle(ctx, world);
			sounds(ctx, world);
			disguise(ctx, world);
			rules(ctx, world);
			progress(ctx, world);
			death(ctx, world);
		}
		Morph.LOGGER.info("MORPH-TEST PASS");
	}

	/** Morphing keeps the fraction of health, so a big mob and back is not a heal (it used to fill health to the mob's max). */
	private static void health(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphState.unlock(player, EntityTypes.IRON_GOLEM);
			MorphState.unlock(player, EntityTypes.CHICKEN);
			player.setHealth(5.0F);
			MorphState.morph(player, EntityTypes.IRON_GOLEM);
			check(player.getHealth() == 25.0F, "golem: health " + player.getHealth() + ", want 25 (a quarter of 100)");
			MorphState.unmorph(player);
			check(player.getHealth() == 5.0F, "unmorphed from golem: health " + player.getHealth() + ", want 5");
			// Switching back and forth, through a body much smaller than the player's, never adds health.
			for (int i = 0; i < 20; i++) {
				MorphState.morph(player, i % 2 == 0 ? EntityTypes.IRON_GOLEM : EntityTypes.CHICKEN);
			}
			MorphState.unmorph(player);
			check(player.getHealth() <= 5.0F && player.getHealth() > 4.9F, "after switching: health " + player.getHealth() + ", want about 5");
			player.setHealth(1.0F);
			MorphState.morph(player, EntityTypes.CHICKEN);
			MorphState.unmorph(player);
			check(player.getHealth() <= 1.0F && player.getHealth() > 0.99F, "half a heart: health " + player.getHealth() + ", want 1");
			player.setHealth(player.getMaxHealth());
		});
		Morph.LOGGER.info("MORPH-TEST ok health");
	}

	/** Each look of a mob is its own morph: a red sheep unlocks red, not blue, and the disguise wears the look. */
	private static void variants(ClientGameTestContext ctx, TestSingleplayerContext world) {
		// Old saves stored bare ids; they must still read as the default look.
		MorphVariant legacy = MorphVariant.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf("minecraft:pig")).getOrThrow();
		check(legacy.equals(MorphVariant.of(EntityTypes.PIG)), "legacy id didn't decode to the default pig: " + legacy);

		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			ServerLevel level = player.level();
			Sheep sheep = EntityTypes.SHEEP.create(level, EntitySpawnReason.COMMAND);
			sheep.setColor(DyeColor.RED);
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			zombie.setBaby(true);
			for (Mob mob : List.<Mob>of(sheep, zombie)) {
				mob.setPos(player.getX() + 2, player.getY(), player.getZ());
				level.addFreshEntity(mob);
				mob.hurtServer(level, player.damageSources().playerAttack(player), 1000);
			}
		});
		ctx.waitTicks(2);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			List<MorphVariant> sheep = MorphState.unlocked(player, EntityTypes.SHEEP);
			check(sheep.size() == 1 && sheep.getFirst().describe(player.level()).equals("red"), "red sheep unlocked as " + sheep);
			List<MorphVariant> zombie = MorphState.unlocked(player, EntityTypes.ZOMBIE);
			check(zombie.size() == 1 && zombie.getFirst().describe(player.level()).equals("baby"), "baby zombie unlocked as " + zombie);
		});

		// Hand-written NBT with the wrong number type still finds the unlocked red sheep; blue stays locked.
		command(world, "morph sheep {Color:11}");
		ctx.waitTicks(2);
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) != EntityTypes.SHEEP,
			"morphed into a blue sheep without unlocking it");
		command(world, "morph sheep {Color:14}");
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> {
			check(MorphClient.disguise(mc.player) instanceof Sheep sheep && sheep.getColor() == DyeColor.RED, "client: disguise isn't a red sheep");
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		});
		ctx.waitTicks(10);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-red-sheep"));

		command(world, "morph zombie");
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> check(MorphClient.disguise(mc.player) instanceof Zombie zombie && zombie.isBaby(), "client: disguise isn't a baby zombie"));
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-baby-zombie"));
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY);
		});
		command(world, "unmorph");
		ctx.waitTicks(5);
	}

	/** Left-clicking a mob while morphed plays the disguise's own attack animation; the warden's power plays its wind-up. */
	private static void animations(ClientGameTestContext ctx, TestSingleplayerContext world) {
		attack(ctx, world, EntityTypes.WARDEN, disguise -> ((Warden) disguise).attackAnimationState.isStarted());
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("warden-attack"));
		attack(ctx, world, EntityTypes.IRON_GOLEM, disguise -> ((IronGolem) disguise).getAttackAnimationTick() > 0);
		attack(ctx, world, EntityTypes.RAVAGER, disguise -> ((Ravager) disguise).getAttackTick() > 0);
		attack(ctx, world, EntityTypes.HOGLIN, disguise -> ((Hoglin) disguise).getAttackAnimationRemainingTicks() > 0);
		attack(ctx, world, EntityTypes.ZOMBIE, disguise -> ((Zombie) disguise).isAggressive());
		attack(ctx, world, EntityTypes.SKELETON, disguise -> ((LivingEntity) disguise).isSwinging());

		world.getServer().runOnServer(server -> {
			MorphState.morph(world.getConnection().getServerPlayer(), EntityTypes.WARDEN);
			MorphPowers.use(world.getConnection().getServerPlayer());
		});
		ctx.waitTicks(3);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> check(MorphClient.disguise(mc.player) instanceof Warden warden && warden.sonicBoomAnimationState.isStarted(),
			"warden: power didn't start the sonic boom animation"));
		ctx.waitTicks(20);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("warden-sonic-boom"));
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY);
		});
		command(world, "unmorph");
		ctx.waitTicks(40);
	}

	/** Morph, face a no-AI cow in reach, press the real attack key, then check the disguise. */
	private static void attack(ClientGameTestContext ctx, TestSingleplayerContext world, EntityType<?> morph, Predicate<Entity> animated) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			ServerLevel level = player.level();
			level.getEntities(player, player.getBoundingBox().inflate(16), e -> e instanceof Mob).forEach(Entity::discard);
			MorphState.unlock(player, morph);
			MorphState.morph(player, morph);
			player.setYRot(0);
			Mob cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
			cow.setNoAi(true);
			cow.setPos(player.getX(), player.getY(), player.getZ() + 2.5);
			level.addFreshEntity(cow);
		});
		ctx.waitTicks(3);
		world.getConnection().waitForClientboundPackets();
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			Entity cow = player.level().getEntities(player, player.getBoundingBox().inflate(8), e -> e.getType() == EntityTypes.COW).getFirst();
			player.lookAt(EntityAnchorArgument.Anchor.EYES, cow.getBoundingBox().getCenter());
		});
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		ctx.getInput().pressKey(options -> options.keyAttack);
		ctx.waitTicks(2);
		world.getConnection().waitForServerboundPackets();
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> {
			check(mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY, morph.toShortString() + ": the cow wasn't under the crosshair");
			check(animated.test(MorphClient.disguise(mc.player)), morph.toShortString() + ": attacking didn't animate the disguise");
		});
		Morph.LOGGER.info("MORPH-TEST ok animation {}", morph.toShortString());
	}

	/** Monsters treat a morphed player like the mob they look like, and fight back whoever hits them. */
	private static void relations(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			ServerLevel level = player.level();
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			Skeleton skeleton = EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
			for (Mob mob : List.<Mob>of(zombie, skeleton)) {
				mob.setPos(player.getX() + 4, player.getY(), player.getZ());
				level.addFreshEntity(mob);
			}
			check(zombie.canAttack(player), "zombie won't attack a plain player");
			for (EntityType<?> type : List.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.CHICKEN, EntityTypes.ZOMBIE)) {
				MorphState.unlock(player, type);
				MorphState.morph(player, type);
				check(!zombie.canAttack(player), "zombie attacks a " + type.toShortString() + " morph");
				check(!skeleton.canAttack(player), "skeleton attacks a " + type.toShortString() + " morph");
			}
			// Their own prey stays prey.
			MorphState.unlock(player, EntityTypes.VILLAGER);
			MorphState.morph(player, EntityTypes.VILLAGER);
			check(zombie.canAttack(player), "zombie ignores a villager morph");
			MorphState.morph(player, EntityTypes.IRON_GOLEM);
			check(skeleton.canAttack(player), "skeleton ignores an iron golem morph");
			// Hit it as a cow and it fights back.
			MorphState.morph(player, EntityTypes.COW);
			zombie.setLastHurtByMob(player);
			check(zombie.canAttack(player), "zombie doesn't fight back against a cow that hit it");
			check(!skeleton.canAttack(player), "skeleton attacks a cow that hit someone else");
			zombie.discard();
			skeleton.discard();
			MorphState.unmorph(player);
		});
		Morph.LOGGER.info("MORPH-TEST ok relations");
	}

	/** Morphing chicken, pig, chicken (or unmorphing) used to clear the cooldown: an egg every couple of seconds. */
	private static void cooldownSurvivesMorphing(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphPowers.clearCooldowns(player);
			MorphState.unlock(player, EntityTypes.CHICKEN);
			MorphState.unlock(player, EntityTypes.PIG);
			MorphState.morph(player, EntityTypes.CHICKEN);
			MorphPowers.use(player);
			long readyAt = player.getAttachedOrElse(MorphPowers.READY_AT, 0L);
			check(readyAt > player.level().getGameTime(), "chicken: laying an egg started no cooldown");
			int eggs = eggs(player);
			MorphState.morph(player, EntityTypes.PIG);
			check(player.getAttachedOrElse(MorphPowers.READY_AT, 0L) == 0L, "pig: shows the chicken's cooldown");
			MorphState.morph(player, EntityTypes.CHICKEN);
			check(player.getAttachedOrElse(MorphPowers.READY_AT, 0L) == readyAt, "chicken again: cooldown was reset");
			MorphPowers.use(player);
			MorphState.unmorph(player);
			MorphState.morph(player, EntityTypes.CHICKEN);
			MorphPowers.use(player);
			check(eggs(player) == eggs, "morphing away and back laid another egg");
			MorphState.unmorph(player);
		});
		Morph.LOGGER.info("MORPH-TEST ok cooldown-survives-morphing");
	}

	private static int eggs(ServerPlayer player) {
		return player.level().getEntities(EntityTypes.ITEM, player.getBoundingBox().inflate(4), item -> item.getItem().is(Items.EGG)).size();
	}

	/** A squid disguise keeps stroking its tentacles; it used to freeze after the first stroke, waiting for the server. */
	private static void squid(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphState.unlock(player, EntityTypes.SQUID);
			MorphState.morph(player, EntityTypes.SQUID);
		});
		ctx.waitTicks(100); // longer than the slowest stroke, 2π at 0.1 a tick
		world.getConnection().waitForClientboundPackets();
		Set<Float> angles = new HashSet<>();
		for (int i = 0; i < 20; i++) {
			angles.add(ctx.computeOnClient(mc -> ((Squid) MorphClient.disguise(mc.player)).tentacleAngle));
			ctx.waitTicks(1);
		}
		check(angles.size() > 5, "squid tentacles stopped moving: " + angles);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-squid"));
		// First person, empty hand: no human arm on a squid.
		int selected = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		ctx.runOnClient(mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			for (int slot = 8; slot >= 0; slot--) {
				if (mc.player.getInventory().getItem(slot).isEmpty()) {
					mc.player.getInventory().setSelectedSlot(slot);
				}
			}
		});
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.player.getMainHandItem().isEmpty()), "no empty hotbar slot for an empty hand");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("first-person-squid-no-arm"));
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(selected));
		command(world, "unmorph");
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST ok squid");
	}

	/** The nautilus breathes and swims underwater and dashes like it does with a rider. */
	private static void nautilus(ClientGameTestContext ctx, TestSingleplayerContext world) {
		for (EntityType<?> type : List.of(EntityTypes.NAUTILUS, EntityTypes.ZOMBIE_NAUTILUS)) {
			// Asked on the client: the table reaches it from the server's data files.
			Set<MorphAbilities.Ability> abilities = ctx.computeOnClient(mc -> MorphAbilities.of(type, mc.level));
			check(abilities.containsAll(Set.of(MorphAbilities.Ability.WATER_BREATHING, MorphAbilities.Ability.SWIM)),
				type.toShortString() + ": abilities " + abilities);
			check(ctx.computeOnClient(mc -> MorphPowers.name(type, mc.level)) != null, type.toShortString() + ": no power on the client");
			check(world.getServer().computeOnServer(server -> MorphPowers.of(type)) != null, type.toShortString() + ": no power");
		}
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphState.unlock(player, EntityTypes.NAUTILUS);
			MorphState.morph(player, EntityTypes.NAUTILUS);
		});
		ctx.waitTicks(25); // one ability refresh
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(player.hasEffect(MobEffects.WATER_BREATHING), "nautilus: no water breathing");
			MorphState.unmorph(player);
		});
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST ok nautilus");
	}

	/**
	 * The phantom glides like an elytra wearer without wearing one, and flapping (jump while gliding) lifts it off flat
	 * ground. Real key presses: jump, jump again in the air to glide, then flap. Gliding past 20 ticks also covers the
	 * elytra wear check, which used to pick an elytra slot from none.
	 */
	private static void phantom(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphState.unlock(player, EntityTypes.PHANTOM);
			MorphState.morph(player, EntityTypes.PHANTOM);
			check(!player.getAbilities().mayfly, "phantom: has creative flight");
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		double ground = ctx.computeOnClient(mc -> mc.player.getY());
		Vec3 takeoff = world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().position());
		ctx.runOnClient(mc -> {
			mc.player.setXRot(-20);
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		ctx.getInput().holdKeyFor(options -> options.keyJump, 2); // off the ground
		ctx.waitTicks(4);
		ctx.getInput().holdKeyFor(options -> options.keyJump, 2); // in the air: glide
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> mc.player.isFallFlying()), "phantom: jumping in the air didn't start gliding");
		double highest = ground;
		for (int flap = 0; flap < 6; flap++) {
			ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
			for (int tick = 0; tick < MorphPowers.FLAP_COOLDOWN + 1; tick++) {
				ctx.waitTicks(1);
				highest = Math.max(highest, ctx.computeOnClient(mc -> mc.player.getY()));
			}
			Morph.LOGGER.info("MORPH-TEST debug phantom flap {}: y {}", flap, ctx.computeOnClient(mc -> mc.player.getY()));
		}
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("phantom-gliding"));
		// The body follows the flight path, not the camera.
		ctx.computeOnClient(mc -> {
			Vec3 motion = mc.player.getDeltaMovement();
			float path = (float) -Math.toDegrees(Math.atan2(motion.y, motion.horizontalDistance()));
			float body = MorphClient.disguise(mc.player).getXRot();
			check(Math.abs(body - path) < 15, "phantom: body pitch " + body + " doesn't follow the flight path " + path);
			return null;
		});
		// Turn right: the view leads the flight, so it banks right.
		for (int tick = 0; tick < 6; tick++) {
			ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 8));
			ctx.waitTicks(1);
		}
		float bank = ctx.computeOnClient(mc -> MorphClient.bank(MorphClient.disguise(mc.player), 1.0F));
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("phantom-banking-right"));
		check(bank > 10, "phantom: no right bank while turning right: " + bank);
		check(highest > ground + 4, "phantom: flapping didn't climb (" + ground + " -> " + highest + ")");
		check(ctx.computeOnClient(mc -> mc.player.isFallFlying()), "phantom: stopped gliding mid-air");
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().isFallFlying()), "server: phantom isn't gliding");

		// Back as a player, without an elytra: no gliding, and the glide ends.
		ctx.runOnClient(mc -> mc.player.setXRot(0));
		command(world, "unmorph");
		ctx.waitTicks(5);
		check(!world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().isFallFlying()), "unmorphed: still gliding");
		// Put the player back down rather than fall from the top of the climb.
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.teleportTo(takeoff.x, takeoff.y, takeoff.z);
			player.resetFallDistance();
			player.setDeltaMovement(Vec3.ZERO);
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		});
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY);
		});
		world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().setHealth(20.0F));
		Morph.LOGGER.info("MORPH-TEST ok phantom {} -> {}", ground, highest);
	}

	/** A humanoid disguise holds the player's items, in the same hands, and lets go when the player does. */
	private static void heldItems(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphState.unlock(player, EntityTypes.ZOMBIE);
			MorphState.morph(player, EntityTypes.ZOMBIE);
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
			player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> {
			Entity disguise = MorphClient.disguise(mc.player);
			check(disguise instanceof Zombie zombie && zombie.getMainHandItem().is(Items.DIAMOND_SWORD)
				&& zombie.getOffhandItem().is(Items.SHIELD), "zombie disguise isn't holding the sword and shield");
			mc.player.setYRot(180);
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		});
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("zombie-holding-sword"));

		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> {
			LivingEntity disguise = (LivingEntity) MorphClient.disguise(mc.player);
			check(disguise.getMainHandItem().isEmpty() && disguise.getOffhandItem().isEmpty(), "zombie disguise kept the items after they were put away");
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		});
		command(world, "unmorph");
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST ok held-items");
	}

	/**
	 * Unlock enough mobs to overflow the sidebar, open it with the key, scroll, and click a mob with the mouse. Then expand
	 * the sheep, whose looks share one row, and pick the red one from its tiles.
	 */
	private static void sidebar(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			for (EntityType<?> type : List.of(EntityTypes.BAT, EntityTypes.BEE, EntityTypes.BLAZE, EntityTypes.CAT, EntityTypes.CHICKEN,
				EntityTypes.COD, EntityTypes.COW, EntityTypes.CREEPER, EntityTypes.DOLPHIN, EntityTypes.ENDERMAN, EntityTypes.FOX,
				EntityTypes.GHAST, EntityTypes.HORSE, EntityTypes.IRON_GOLEM, EntityTypes.PIG, EntityTypes.RABBIT, EntityTypes.SHEEP,
				EntityTypes.SPIDER, EntityTypes.SQUID, EntityTypes.WOLF, EntityTypes.ZOMBIE)) {
				MorphState.unlock(player, type);
			}
			// With the red sheep from variants(), three looks of sheep.
			MorphState.unlock(player, sheep(player.level(), DyeColor.BLUE));
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> mc.gui.toastManager().clear()); // recipe toasts cover the panel header

		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-top"));

		// The cursor must be over the list for the wheel to reach it.
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		int panelX = ctx.computeOnClient(mc -> sidebar(mc).panelLeft()) + 40;
		int listTop = ctx.computeOnClient(mc -> sidebar(mc).listTop());
		ctx.getInput().setCursorPos(panelX * scale, (listTop + 40) * scale);
		ctx.getInput().scroll(-30);
		ctx.waitTicks(5);
		double scrolled = ctx.computeOnClient(mc -> sidebar(mc).scroll());
		check(scrolled > 0, "sidebar didn't scroll");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-scrolled"));

		// Click the spider row, wherever scrolling left it, wheeling back up if it went past.
		for (int i = 0; i < 10 && ctx.computeOnClient(mc -> sidebar(mc).rowTop(EntityTypes.SPIDER)) < listTop; i++) {
			ctx.getInput().scroll(1);
			ctx.waitTicks(1);
		}
		int spiderTop = ctx.computeOnClient(mc -> sidebar(mc).rowTop(EntityTypes.SPIDER));
		check(spiderTop >= listTop, "spider row scrolled out of view: " + spiderTop);
		click(ctx, scale, panelX, spiderTop + MorphSidebarScreen.ENTRY_HEIGHT / 2.0);
		ctx.waitTicks(25);
		world.getConnection().waitForClientboundPackets();
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.SPIDER,
			"clicking the spider row didn't morph into a spider");
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "sidebar didn't close after picking");

		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(5);
		int spiderRow = ctx.computeOnClient(mc -> sidebar(mc).rowTop(EntityTypes.SPIDER));
		int screenHeight = ctx.computeOnClient(mc -> mc.gui.screen().height);
		check(spiderRow >= listTop && spiderRow + MorphSidebarScreen.ENTRY_HEIGHT <= screenHeight,
			"reopened sidebar doesn't show the current morph: row at " + spiderRow);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-spider-selected"));

		// The sheep's looks are folded into one row until its arrow is clicked.
		check(!ctx.computeOnClient(mc -> sidebar(mc).isExpanded(EntityTypes.SHEEP)), "sheep looks open before asking");
		int sheepTop = ctx.computeOnClient(mc -> sidebar(mc).rowTop(EntityTypes.SHEEP));
		check(sheepTop >= listTop, "sheep row out of view: " + sheepTop);
		int arrowX = ctx.computeOnClient(mc -> mc.gui.screen().width) - MorphSidebarScreen.ARROW_WIDTH / 2 - 2;
		click(ctx, scale, arrowX, sheepTop + MorphSidebarScreen.ENTRY_HEIGHT / 2.0);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> sidebar(mc).isExpanded(EntityTypes.SHEEP)), "the arrow didn't open the sheep's looks");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MorphSidebarScreen), "opening the looks closed the sidebar");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-sheep-expanded"));
		click(ctx, scale, panelX, sheepTop + MorphSidebarScreen.ENTRY_HEIGHT / 2.0, InputConstants.MOUSE_BUTTON_RIGHT);
		ctx.waitTicks(2);
		check(!ctx.computeOnClient(mc -> sidebar(mc).isExpanded(EntityTypes.SHEEP)), "right-clicking the open sheep row didn't fold it");
		click(ctx, scale, arrowX, sheepTop + MorphSidebarScreen.ENTRY_HEIGHT / 2.0);
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> sidebar(mc).isExpanded(EntityTypes.SHEEP)), "the arrow didn't reopen the sheep's looks");

		MorphVariant red = ctx.computeOnClient(mc -> sheep(mc.level, DyeColor.RED));
		ScreenPosition redTile = ctx.computeOnClient(mc -> sidebar(mc).tileCenter(red));
		check(redTile != null && redTile.y() >= listTop, "red sheep tile not shown: " + redTile);
		click(ctx, scale, redTile.x(), redTile.y());
		ctx.waitTicks(25);
		world.getConnection().waitForClientboundPackets();
		check(red.equals(world.getServer().computeOnServer(s -> MorphState.currentVariant(world.getConnection().getServerPlayer()))),
			"clicking the red tile didn't morph into a red sheep");

		// Reopened as a red sheep, the sheep's looks open by themselves.
		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> sidebar(mc).isExpanded(EntityTypes.SHEEP)), "current morph's looks aren't open");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-red-sheep-selected"));
		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "the sidebar key didn't close the sidebar");
	}

	/** Typing in the search box narrows the list; M types instead of closing; Enter morphs into the first match. */
	private static void search(ClientGameTestContext ctx, TestSingleplayerContext world) {
		command(world, "unmorph");
		ctx.waitTicks(5);
		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(2);
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		int panelX = ctx.computeOnClient(mc -> sidebar(mc).panelLeft()) + 40;
		click(ctx, scale, panelX, MorphSidebarScreen.SEARCH_TOP + MorphSidebarScreen.SEARCH_HEIGHT / 2.0);
		ctx.waitTicks(2);
		ctx.getInput().typeChars("m");
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MorphSidebarScreen), "typing m in the search box closed the sidebar");
		ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		ctx.getInput().typeChars("spid");
		ctx.waitTicks(2);
		List<EntityType<?>> listed = ctx.computeOnClient(mc -> sidebar(mc).listed());
		check(listed.equals(List.of(EntityTypes.SPIDER)), "searching spid lists " + listed);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-search"));
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		ctx.waitTicks(25);
		world.getConnection().waitForClientboundPackets();
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.SPIDER,
			"Enter in the search box didn't morph into the match");
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "sidebar didn't close after Enter");
		Morph.LOGGER.info("MORPH-TEST ok search");
	}

	/** F over a row stars it; the radial key shows starred morphs and letting go over one morphs into it. */
	private static void favorites(ClientGameTestContext ctx, TestSingleplayerContext world) {
		// Only unlocked looks can be starred.
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(!MorphState.isUnlocked(player, EntityTypes.SNIFFER), "sniffer unlocked before the test");
			check(!MorphState.toggleFavorite(player, MorphVariant.of(EntityTypes.SNIFFER)), "starred a locked sniffer");
			check(player.getAttachedOrElse(MorphState.FAVORITES, List.of()).isEmpty(), "favourites not empty: "
				+ player.getAttached(MorphState.FAVORITES));
		});

		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(2);
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		int panelX = ctx.computeOnClient(mc -> sidebar(mc).panelLeft()) + 40;
		int listTop = ctx.computeOnClient(mc -> sidebar(mc).listTop());
		ctx.getInput().setCursorPos(panelX * scale, (listTop + 40) * scale);
		for (int i = 0; i < 40 && ctx.computeOnClient(mc -> sidebar(mc).rowTop(EntityTypes.BLAZE)) < listTop; i++) {
			ctx.getInput().scroll(1);
			ctx.waitTicks(1);
		}
		int blazeTop = ctx.computeOnClient(mc -> sidebar(mc).rowTop(EntityTypes.BLAZE));
		check(blazeTop >= listTop, "blaze row out of view: " + blazeTop);
		ctx.getInput().setCursorPos(panelX * scale, (blazeTop + MorphSidebarScreen.ENTRY_HEIGHT / 2.0) * scale);
		ctx.getInput().pressKey(InputConstants.KEY_F);
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.waitTicks(2);
		check(world.getServer().computeOnServer(s -> MorphState.isFavorite(world.getConnection().getServerPlayer(), MorphVariant.of(EntityTypes.BLAZE))),
			"F over the blaze row didn't star it");
		check(ctx.computeOnClient(mc -> MorphState.isFavorite(mc.player, MorphVariant.of(EntityTypes.BLAZE))), "client: blaze star not synced");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-starred"));
		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitTicks(2);

		// Hold the radial key, point at the blaze, let go.
		ctx.getInput().holdKey(MorphClient.RADIAL);
		ctx.waitForScreen(MorphRadialScreen.class);
		ctx.waitTicks(2);
		List<MorphRadialScreen.Entry> entries = ctx.computeOnClient(mc -> List.copyOf(((MorphRadialScreen) mc.gui.screen()).entries()));
		check(entries.size() == 2 && entries.get(0).variant() == null && MorphVariant.of(EntityTypes.BLAZE).equals(entries.get(1).variant()),
			"radial entries: " + entries.stream().map(entry -> entry.name().getString()).toList());
		ScreenPosition blazeSlot = ctx.computeOnClient(mc -> ((MorphRadialScreen) mc.gui.screen()).slotCenter(1));
		ctx.getInput().setCursorPos(blazeSlot.x() * scale, blazeSlot.y() * scale);
		ctx.waitTicks(2);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("radial-blaze"));
		ctx.getInput().releaseKey(MorphClient.RADIAL);
		ctx.waitTicks(25);
		world.getConnection().waitForClientboundPackets();
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.BLAZE,
			"letting go of the radial key over the blaze didn't morph into one");
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "radial menu still open after picking");

		// Held, then let go in the middle: nothing changes.
		ctx.getInput().holdKey(MorphClient.RADIAL);
		ctx.waitForScreen(MorphRadialScreen.class);
		int middleX = ctx.computeOnClient(mc -> mc.gui.screen().width / 2);
		int middleY = ctx.computeOnClient(mc -> mc.gui.screen().height / 2);
		ctx.getInput().setCursorPos(middleX * scale, middleY * scale);
		ctx.waitTicks(8);
		ctx.getInput().releaseKey(MorphClient.RADIAL);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "letting go in the middle didn't close the radial menu");
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.BLAZE,
			"letting go in the middle changed the morph");
		Morph.LOGGER.info("MORPH-TEST ok favorites");
	}

	/** The toggle key goes back to yourself, then back into the last mob. */
	private static void toggle(ClientGameTestContext ctx, TestSingleplayerContext world) {
		command(world, "morph spider");
		ctx.waitTicks(5);
		ctx.getInput().pressKey(MorphClient.TOGGLE);
		ctx.waitTicks(5);
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == null,
			"toggle didn't unmorph the spider");
		ctx.getInput().pressKey(MorphClient.TOGGLE);
		ctx.waitTicks(5);
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.SPIDER,
			"toggle didn't go back into the spider");
		command(world, "unmorph");
		ctx.waitTicks(2);
		Morph.LOGGER.info("MORPH-TEST ok toggle");
	}

	/** A morphed player sounds like the mob: hurt, death, fall, steps and idle sounds, at the mob's volume and pitch. */
	private static void sounds(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			MorphState.unlock(player, EntityTypes.ZOMBIE);
			MorphState.unlock(player, EntityTypes.GHAST);
			// The adult: an earlier test unlocked a baby zombie first, and /morph zombie picks the first look.
			MorphState.morph(player, MorphVariant.of(EntityTypes.ZOMBIE));
			LivingEntityInvoker voice = (LivingEntityInvoker) player;
			check(voice.morph$getHurtSound(player.damageSources().generic()) == SoundEvents.ZOMBIE_HURT, "zombie morph: hurt sound isn't the zombie's");
			check(voice.morph$getDeathSound() == SoundEvents.ZOMBIE_DEATH, "zombie morph: death sound isn't the zombie's");
			check(player.getFallSounds().small() == SoundEvents.HOSTILE_SMALL_FALL, "zombie morph: fall sound " + player.getFallSounds());
			float pitch = player.getVoicePitch();
			check(pitch >= 0.8F && pitch <= 1.2F, "zombie morph: voice pitch " + pitch);
			MorphState.morph(player, MorphVariant.parse(EntityTypes.ZOMBIE, babyTag(), player.level()));
			check(player.getVoicePitch() > 1.25F, "baby zombie morph: voice pitch " + player.getVoicePitch() + ", want higher");
			MorphState.morph(player, EntityTypes.GHAST);
			check(voice.morph$getSoundVolume() == 5.0F, "ghast morph: volume " + voice.morph$getSoundVolume() + ", want 5");
			MorphState.unmorph(player);
			check(voice.morph$getHurtSound(player.damageSources().generic()) == SoundEvents.PLAYER_HURT, "unmorphed: hurt sound not the player's");
			MorphState.morph(player, MorphVariant.of(EntityTypes.ZOMBIE));
		});
		ctx.waitTicks(2);
		world.getConnection().waitForClientboundPackets();

		// What the client actually plays, heard the way subtitles hear it.
		Set<Identifier> heard = java.util.Collections.synchronizedSet(new HashSet<>());
		ctx.runOnClient(mc -> mc.getSoundManager().addListener((sound, event, range) -> heard.add(sound.getIdentifier())));
		ctx.runOnClient(mc -> ((EntityInvoker) mc.player).morph$playStepSound(mc.player.blockPosition().below(),
			mc.level.getBlockState(mc.player.blockPosition().below())));
		check(heard.contains(SoundEvents.ZOMBIE_STEP.location()), "zombie morph: client step sound isn't the zombie's: " + heard);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			// A hit right after another (within half a second) that isn't harder is ignored without a sound: clear that.
			player.damageCooldownTime = 0;
			check(player.hurtServer(player.level(), player.damageSources().generic(), 1.0F), "zombie morph: the test hit didn't land");
			// The idle sound is a dice roll that gets likelier every tick (Mob.baseTick); a thousand ticks always roll it.
			for (int i = 0; i < 1100; i++) {
				MorphSounds.tick(player);
			}
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.waitTicks(2);
		check(heard.contains(SoundEvents.ZOMBIE_HURT.location()), "zombie morph: client didn't play the zombie's hurt sound: " + heard);
		check(heard.contains(SoundEvents.ZOMBIE_AMBIENT.location()), "zombie morph: no idle groan: " + heard);
		check(!heard.contains(SoundEvents.PLAYER_HURT.location()), "zombie morph: client still played the player's hurt sound");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.setHealth(player.getMaxHealth());
			MorphState.unmorph(player);
		});
		ctx.waitTicks(2);
		Morph.LOGGER.info("MORPH-TEST ok sounds");
	}

	/**
	 * A disguise: no nametag unless morph:show_nametags is on, the player's armour on humanoid mobs unless
	 * morph:show_armor is off, and a shrink-and-grow when changing body.
	 */
	private static void disguise(ClientGameTestContext ctx, TestSingleplayerContext world) {
		// Look at the player from a pig, as another player would see them; extract them the way the world does.
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			// Armour for the zombie to wear. The helmet also keeps it from burning in the sun, out of the screenshots.
			player.clearFire();
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
			Mob pig = EntityTypes.PIG.create(player.level(), EntitySpawnReason.COMMAND);
			pig.setPos(player.getX() + 3, player.getY(), player.getZ());
			pig.setNoAi(true);
			pig.addTag("morph_camera");
			player.level().addFreshEntity(pig);
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> {
			Entity pig = null;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.PIG && entity.distanceTo(mc.player) < 4) {
					pig = entity;
				}
			}
			check(pig != null, "camera pig not on the client");
			mc.setCameraEntity(pig);
		});
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> nameTag(mc) != null), "unmorphed player has no nametag (test can't see names)");
		world.getServer().runOnServer(server -> MorphState.morph(world.getConnection().getServerPlayer(), MorphVariant.of(EntityTypes.ZOMBIE)));
		ctx.waitTicks(25);
		world.getConnection().waitForClientboundPackets();
		check(ctx.computeOnClient(mc -> nameTag(mc)) == null, "show_nametags off: morphed player still has a nametag");
		world.getServer().runCommand("gamerule morph:show_nametags true");
		ctx.waitTicks(2);
		world.getConnection().waitForClientboundPackets();
		check(ctx.computeOnClient(mc -> MorphRules.clientRules(mc.level).showNametags()), "show_nametags didn't reach the client");
		String name = ctx.computeOnClient(mc -> {
			net.minecraft.network.chat.Component tag = nameTag(mc);
			return tag == null ? null : tag.getString();
		});
		check(name != null && name.equals(ctx.computeOnClient(mc -> mc.player.getName().getString())),
			"show_nametags on: morphed player's nametag is " + name);
		world.getServer().runCommand("gamerule morph:show_nametags false");
		ctx.runOnClient(mc -> {
			mc.setCameraEntity(mc.player);
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		});
		world.getServer().runCommand("kill @e[tag=morph_camera]");

		// Worn armour on a zombie.
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> {
			LivingEntity zombie = (LivingEntity) MorphClient.disguise(mc.player);
			check(zombie.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "zombie morph: no helmet on the disguise");
			check(zombie.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.GOLDEN_CHESTPLATE), "zombie morph: no chestplate on the disguise");
		});
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-zombie-armor"));
		world.getServer().runCommand("gamerule morph:show_armor false");
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		check(ctx.computeOnClient(mc -> ((LivingEntity) MorphClient.disguise(mc.player)).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
			.isEmpty()), "show_armor off: disguise still wears the helmet");
		world.getServer().runCommand("gamerule morph:show_armor true");

		// Changing body: both bodies drawn, the old one shrinking, then just the new one.
		command(world, "morph spider");
		ctx.waitTicks(1);
		world.getConnection().waitForClientboundPackets();
		ctx.waitTicks(4);
		ctx.runOnClient(mc -> {
			float progress = MorphClient.transition(mc.player, 0.0F);
			check(progress >= 0.0F && progress < 1.0F, "zombie to spider: no transition (" + progress + ")");
			List<EntityRenderState> states = MorphClient.extract(mc.player, 0.0F, e -> mc.getEntityRenderDispatcher().extractEntity(e, 0.0F));
			check(states.size() == 2, "zombie to spider: drew " + states.size() + " bodies mid-change, want 2");
			check(states.get(0).entityType == EntityTypes.SPIDER && states.get(1).entityType == EntityTypes.ZOMBIE,
				"zombie to spider: drew " + states.get(0).entityType + " and " + states.get(1).entityType);
			float spider = ((LivingEntityRenderState) states.get(0)).scale;
			float zombie = ((LivingEntityRenderState) states.get(1)).scale;
			check(spider < 1.0F && zombie < 1.0F, "zombie to spider: scales " + spider + ", " + zombie + " mid-change");
		});
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-transition"));
		ctx.waitTicks(MorphClient.TRANSITION_TICKS + 5);
		ctx.runOnClient(mc -> {
			check(MorphClient.transition(mc.player, 0.0F) < 0.0F, "zombie to spider: transition never ended");
			List<EntityRenderState> states = MorphClient.extract(mc.player, 0.0F, e -> mc.getEntityRenderDispatcher().extractEntity(e, 0.0F));
			check(states.size() == 1 && ((LivingEntityRenderState) states.get(0)).scale == 1.0F, "spider: not back to one full-size body");
		});
		command(world, "unmorph");
		ctx.waitTicks(3);
		world.getConnection().waitForClientboundPackets();
		check(ctx.computeOnClient(mc -> MorphClient.transition(mc.player, 0.0F) >= 0.0F), "spider to player: no transition");
		ctx.waitTicks(MorphClient.TRANSITION_TICKS + 5);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY);
		});
		Morph.LOGGER.info("MORPH-TEST ok disguise");
	}

	/** The nametag drawn over the local player, as seen from the camera entity. */
	private static net.minecraft.network.chat.@org.jspecify.annotations.Nullable Component nameTag(Minecraft mc) {
		return MorphClient.extract(mc.player, 0.0F, e -> mc.getEntityRenderDispatcher().extractEntity(e, 0.0F)).getFirst().nameTag;
	}

	private static net.minecraft.nbt.CompoundTag babyTag() {
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		tag.putBoolean("IsBaby", true);
		return tag;
	}

	/** A kill shows a toast instead of a chat line; milestones award the Morphed advancements. */
	private static void progress(ClientGameTestContext ctx, TestSingleplayerContext world) {
		check(!world.getServer().computeOnServer(s -> MorphState.isUnlocked(world.getConnection().getServerPlayer(), EntityTypes.MOOSHROOM)),
			"mooshroom already unlocked");
		ctx.runOnClient(mc -> mc.gui.toastManager().clear());
		kill(world, EntityTypes.MOOSHROOM);
		ctx.waitTicks(2);
		world.getConnection().waitForClientboundPackets();
		ctx.waitTicks(2);
		MorphUnlockToast toast = ctx.computeOnClient(mc -> mc.gui.toastManager().getToast(MorphUnlockToast.class, Toast.NO_TOKEN));
		check(toast != null && toast.variant().type() == EntityTypes.MOOSHROOM, "killing a mooshroom didn't show its unlock toast");
		// Toasts slide in on the wall clock, and test ticks run faster than that.
		realTime(ctx, 1500);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-unlock-toast"));

		Set<EntityType<?>> collectable = MorphProgress.collectable();
		Morph.LOGGER.info("MORPH-TEST {} collectable mobs", collectable.size());
		check(collectable.contains(EntityTypes.ZOMBIE) && collectable.contains(EntityTypes.WARDEN), "zombie or warden not collectable");
		check(!collectable.contains(EntityTypes.GIANT) && !collectable.contains(EntityTypes.ILLUSIONER), "giant or illusioner counted");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(done(server, player, "root"), "first unlock didn't award morph:root");
			int collected = MorphProgress.collected(player, collectable);
			check(collected < 10 || done(server, player, "ten_morphs"), collected + " mobs but no morph:ten_morphs");
			check(!done(server, player, "all_morphs"), "morph:all_morphs with " + collected + " mobs");
			List<EntityType<?>> missing = collectable.stream().filter(type -> !MorphState.isUnlocked(player, type)).toList();
			for (EntityType<?> type : missing.subList(0, missing.size() - 1)) {
				MorphState.unlock(player, type);
			}
			check(done(server, player, "fifty_morphs"), "no morph:fifty_morphs with all but one mob");
			check(!done(server, player, "all_morphs"), "morph:all_morphs with one mob still missing");
			MorphState.unlock(player, missing.getLast());
			check(done(server, player, "all_morphs"), "no morph:all_morphs with every mob");
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> mc.gui.toastManager().clear());
		ctx.setScreen(MorphSidebarScreen::new);
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-sidebar-progress"));
		// The Morphed tab, selected.
		ctx.setScreen(() -> {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.multiplayer.ClientAdvancements advancements = mc.player.connection.getAdvancements();
			net.minecraft.client.gui.screens.advancements.AdvancementsScreen screen =
				new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(advancements);
			advancements.setSelectedTab(advancements.get(Morph.id("root")), true);
			return screen;
		});
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("morph-advancements"));
		ctx.setScreen(() -> null);
		Morph.LOGGER.info("MORPH-TEST ok progress");
	}

	private static void realTime(ClientGameTestContext ctx, long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		ctx.waitTicks(1);
	}

	private static boolean done(net.minecraft.server.MinecraftServer server, ServerPlayer player, String advancement) {
		AdvancementHolder holder = server.getAdvancements().get(Morph.id(advancement));
		return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
	}

	/** The morph: gamerules: kills to unlock, morph on unlock, flight, monster behaviour. */
	private static void rules(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runCommand("gamerule morph:kills_to_unlock 3");
		for (int i = 1; i <= 3; i++) {
			kill(world, EntityTypes.POLAR_BEAR);
			ctx.waitTicks(2);
			int kills = i;
			world.getServer().runOnServer(server -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				boolean unlocked = MorphState.isUnlocked(player, EntityTypes.POLAR_BEAR);
				check(unlocked == (kills == 3), "polar bear unlocked: " + unlocked + " after " + kills + " of 3 kills");
			});
		}
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().getAttachedOrElse(MorphState.KILLS, java.util.Map.of())
			.isEmpty()), "kill count kept after unlocking");
		world.getServer().runCommand("gamerule morph:kills_to_unlock 1");

		world.getServer().runCommand("gamerule morph:morph_on_unlock true");
		kill(world, EntityTypes.GOAT);
		ctx.waitTicks(2);
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.GOAT,
			"morph_on_unlock didn't turn the player into the goat");
		world.getServer().runCommand("gamerule morph:morph_on_unlock false");

		world.getServer().runCommand("gamerule morph:allow_flight false");
		command(world, "morph parrot");
		ctx.waitTicks(25);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(!player.getAbilities().mayfly, "parrot can fly with allow_flight off");
			check(player.hasEffect(MobEffects.SLOW_FALLING), "parrot doesn't flutter with allow_flight off");
		});
		world.getServer().runCommand("gamerule morph:allow_flight true");
		ctx.waitTicks(25);
		check(world.getServer().computeOnServer(s -> world.getConnection().getServerPlayer().getAbilities().mayfly),
			"parrot can't fly after allow_flight went back on");

		// flight_needs_advancement: no flying until morph:flight, which follows vanilla's "The End?".
		world.getServer().runCommand("gamerule morph:flight_needs_advancement true");
		ctx.waitTicks(25);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(!player.getAbilities().mayfly, "parrot can fly before earning flight");
			check(player.hasEffect(MobEffects.SLOW_FALLING), "parrot doesn't flutter before earning flight");
		});
		world.getServer().runCommand("advancement grant @a only minecraft:end/root");
		ctx.waitTicks(25);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			AdvancementHolder flight = server.getAdvancements().get(MorphRules.FLIGHT_ADVANCEMENT);
			check(flight != null && player.getAdvancements().getOrStartProgress(flight).isDone(),
				"entering the End didn't grant morph:flight");
			check(player.getAbilities().mayfly, "parrot can't fly after earning flight");
		});
		world.getServer().runCommand("advancement revoke @a only morph:flight");
		world.getServer().runCommand("advancement revoke @a only minecraft:end/root");
		world.getServer().runCommand("gamerule morph:flight_needs_advancement false");

		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			ServerLevel level = player.level();
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			zombie.setPos(player.getX() + 4, player.getY(), player.getZ());
			level.addFreshEntity(zombie);
			MorphState.morph(player, EntityTypes.COW);
			check(!zombie.canAttack(player), "disguise: zombie attacks a cow");
			server.getGameRules().set(MorphRules.MONSTER_BEHAVIOR, MorphRules.MonsterBehavior.MONSTERS_ONLY, server);
			check(zombie.canAttack(player), "monsters_only: zombie ignores a cow");
			MorphState.morph(player, EntityTypes.ZOMBIE);
			check(!zombie.canAttack(player), "monsters_only: zombie attacks a zombie");
			check(player.getVisibilityPercent(level, zombie) == 1.0, "monsters_only: zombie sees a zombie from less far");
			server.getGameRules().set(MorphRules.MONSTER_BEHAVIOR, MorphRules.MonsterBehavior.SHORT_RANGE, server);
			check(zombie.canAttack(player), "short_range: zombie ignores a zombie");
			check(player.getVisibilityPercent(level, zombie) == 0.5, "short_range: zombie sees a zombie from as far as ever");
			MorphState.morph(player, EntityTypes.VILLAGER);
			check(player.getVisibilityPercent(level, zombie) == 1.0, "short_range: zombie sees a villager from less far");
			MorphState.morph(player, EntityTypes.ZOMBIE);
			server.getGameRules().set(MorphRules.MONSTER_BEHAVIOR, MorphRules.MonsterBehavior.OFF, server);
			check(zombie.canAttack(player), "off: zombie ignores a zombie");
			check(player.getVisibilityPercent(level, zombie) == 1.0, "off: zombie sees a zombie from less far");
			server.getGameRules().set(MorphRules.MONSTER_BEHAVIOR, MorphRules.MonsterBehavior.DISGUISE, server);
			zombie.discard();
			MorphState.unmorph(player);
		});
		// The enum rule takes its constant names on the command line.
		world.getServer().runCommand("gamerule morph:monster_behavior OFF");
		check(world.getServer().computeOnServer(s -> s.getGameRules().get(MorphRules.MONSTER_BEHAVIOR)) == MorphRules.MonsterBehavior.OFF,
			"/gamerule didn't set monster_behavior");
		world.getServer().runCommand("gamerule morph:monster_behavior DISGUISE");
		Morph.LOGGER.info("MORPH-TEST ok rules");
	}

	/** Morphs survive death by default; with keep_morphs_on_death off, a death forgets them. Runs last: it wipes the unlocks. */
	private static void death(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runCommand("gamerule immediate_respawn true");
		world.getServer().runCommand("kill @a");
		ctx.waitTicks(20);
		world.getConnection().waitForClientboundPackets();
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(player.isAlive(), "player didn't respawn");
			check(MorphState.isUnlocked(player, EntityTypes.SPIDER), "a death forgot morphs with keep_morphs_on_death on");
			check(MorphState.isFavorite(player, MorphVariant.of(EntityTypes.BLAZE)), "a death forgot the starred blaze");
		});
		world.getServer().runCommand("gamerule morph:keep_morphs_on_death false");
		world.getServer().runCommand("kill @a");
		ctx.waitTicks(20);
		world.getConnection().waitForClientboundPackets();
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			check(player.isAlive(), "player didn't respawn");
			check(player.getAttachedOrElse(MorphState.UNLOCKED, List.of()).isEmpty(), "keep_morphs_on_death off: morphs kept "
				+ player.getAttached(MorphState.UNLOCKED));
			check(player.getAttachedOrElse(MorphState.FAVORITES, List.of()).isEmpty(), "keep_morphs_on_death off: favourites kept");
		});
		ctx.runOnClient(mc -> check(mc.player.getAttachedOrElse(MorphState.UNLOCKED, List.of()).isEmpty(), "client: morphs not cleared"));
		world.getServer().runCommand("gamerule morph:keep_morphs_on_death true");
		Morph.LOGGER.info("MORPH-TEST ok death");
	}

	/** Spawns a mob next to the player and kills it as the player. */
	private static void kill(TestSingleplayerContext world, EntityType<?> type) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			ServerLevel level = player.level();
			Entity entity = type.create(level, EntitySpawnReason.COMMAND);
			entity.setPos(player.getX() + 2, player.getY(), player.getZ());
			level.addFreshEntity(entity);
			entity.hurtServer(level, player.damageSources().playerAttack(player), 1000);
		});
	}

	private static MorphSidebarScreen sidebar(Minecraft mc) {
		return (MorphSidebarScreen) mc.gui.screen();
	}

	private static MorphVariant sheep(Level level, DyeColor color) {
		Sheep sheep = EntityTypes.SHEEP.create(level, EntitySpawnReason.COMMAND);
		sheep.setColor(color);
		return MorphVariant.of(sheep);
	}

	private static void click(ClientGameTestContext ctx, double scale, double x, double y) {
		click(ctx, scale, x, y, InputConstants.MOUSE_BUTTON_LEFT);
	}

	private static void click(ClientGameTestContext ctx, double scale, double x, double y, int button) {
		ctx.getInput().setCursorPos(x * scale, y * scale);
		ctx.getInput().pressMouse(button);
	}

	private static void command(TestSingleplayerContext world, String command) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
		});
	}

	private static void checkHitbox(String side, float width, float height, EntityType<?> type) {
		check(Math.abs(width - type.getWidth()) < 1e-4 && Math.abs(height - type.getHeight()) < 1e-4,
			side + ": hitbox " + width + "x" + height + ", want " + type.getWidth() + "x" + type.getHeight());
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			Morph.LOGGER.error("MORPH-TEST FAIL: {}", message);
			throw new AssertionError(message);
		}
	}
}

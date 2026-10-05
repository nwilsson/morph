package com.noelwilsson.morph.test;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import com.noelwilsson.morph.client.MorphClient;
import com.noelwilsson.morph.client.MorphSidebarScreen;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
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
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.DyeColor;

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
			sidebar(ctx, world);
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

	/** Unlock enough mobs to overflow the sidebar, open it with the key, scroll, and click a mob with the mouse. */
	private static void sidebar(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = world.getConnection().getServerPlayer();
			for (EntityType<?> type : List.of(EntityTypes.BAT, EntityTypes.BEE, EntityTypes.BLAZE, EntityTypes.CAT, EntityTypes.CHICKEN,
				EntityTypes.COD, EntityTypes.COW, EntityTypes.CREEPER, EntityTypes.DOLPHIN, EntityTypes.ENDERMAN, EntityTypes.FOX,
				EntityTypes.GHAST, EntityTypes.HORSE, EntityTypes.IRON_GOLEM, EntityTypes.PIG, EntityTypes.RABBIT, EntityTypes.SHEEP,
				EntityTypes.SPIDER, EntityTypes.SQUID, EntityTypes.WOLF, EntityTypes.ZOMBIE)) {
				MorphState.unlock(player, type);
			}
		});
		ctx.waitTicks(5);
		world.getConnection().waitForClientboundPackets();
		ctx.runOnClient(mc -> mc.gui.toastManager().clear()); // recipe toasts cover the panel header

		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(5);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-top"));

		// The cursor must be over the panel for the wheel to reach it.
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		int panelX = ctx.computeOnClient(mc -> ((MorphSidebarScreen) mc.gui.screen()).panelLeft()) + 40;
		ctx.getInput().setCursorPos(panelX * scale, 100 * scale);
		ctx.getInput().scroll(-30);
		ctx.waitTicks(5);
		double scrolled = ctx.computeOnClient(mc -> ((MorphSidebarScreen) mc.gui.screen()).scroll());
		check(scrolled > 0, "sidebar didn't scroll");
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-scrolled"));

		// Click the spider row, wherever scrolling left it.
		int spiderTop = ctx.computeOnClient(mc -> {
			MorphSidebarScreen screen = (MorphSidebarScreen) mc.gui.screen();
			return screen.entryTop(screen.indexOf(EntityTypes.SPIDER));
		});
		check(spiderTop >= MorphSidebarScreen.HEADER_HEIGHT, "spider row scrolled out of view: " + spiderTop);
		ctx.getInput().setCursorPos(panelX * scale, (spiderTop + MorphSidebarScreen.ENTRY_HEIGHT / 2.0) * scale);
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(25);
		world.getConnection().waitForClientboundPackets();
		check(world.getServer().computeOnServer(s -> MorphState.current(world.getConnection().getServerPlayer())) == EntityTypes.SPIDER,
			"clicking the spider row didn't morph into a spider");
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "sidebar didn't close after picking");

		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitForScreen(MorphSidebarScreen.class);
		ctx.waitTicks(5);
		int spiderRow = ctx.computeOnClient(mc -> {
			MorphSidebarScreen screen = (MorphSidebarScreen) mc.gui.screen();
			return screen.entryTop(screen.indexOf(EntityTypes.SPIDER));
		});
		int screenHeight = ctx.computeOnClient(mc -> mc.gui.screen().height);
		check(spiderRow >= MorphSidebarScreen.HEADER_HEIGHT && spiderRow + MorphSidebarScreen.ENTRY_HEIGHT <= screenHeight,
			"reopened sidebar doesn't show the current morph: row at " + spiderRow);
		Morph.LOGGER.info("MORPH-TEST screenshot {}", ctx.takeScreenshot("sidebar-spider-selected"));
		ctx.getInput().pressKey(MorphClient.OPEN_SIDEBAR);
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "the sidebar key didn't close the sidebar");
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

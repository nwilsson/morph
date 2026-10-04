package com.noelwilsson.morph.test;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.client.MorphClient;
import com.noelwilsson.morph.client.MorphSidebarScreen;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

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

			sidebar(ctx, world);
		}
		Morph.LOGGER.info("MORPH-TEST PASS");
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

package com.noelwilsson.morph.test;

import com.noelwilsson.morph.Morph;
import com.noelwilsson.morph.MorphAbilities;
import com.noelwilsson.morph.MorphEvents;
import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphState;
import com.noelwilsson.morph.MorphVariant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;

/**
 * Mob data files and the events API. The test mod ships data/minecraft/morph/mob/panda.json (a panda that climbs, sees
 * in the dark, throws snowballs and sets things on fire) and a file for a mob from a mod that isn't installed.
 */
public class MorphDataGameTest implements FabricClientGameTest {
	/** Only while a check below wants them to: listeners stay registered for the whole game. */
	private static volatile EntityType<?> refuseMorph;
	private static volatile EntityType<?> refuseUnlock;
	private static final List<String> SEEN = new CopyOnWriteArrayList<>();

	static {
		MorphEvents.ALLOW_MORPH.register((player, to) -> to == null || to.type() != refuseMorph);
		MorphEvents.ALLOW_UNLOCK.register((player, variant) -> variant.type() != refuseUnlock);
		MorphEvents.AFTER_MORPH.register((player, from, to) -> SEEN.add("morph " + name(from) + " -> " + name(to)));
		MorphEvents.AFTER_UNLOCK.register((player, variant) -> SEEN.add("unlock " + name(variant)));
	}

	private static String name(MorphVariant variant) {
		return variant == null ? "self" : variant.id().getPath();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode survival @a");
			ctx.waitTicks(5);
			dataFile(ctx, world);
			events(ctx, world);
		}
		Morph.LOGGER.info("MORPH-TEST DATA PASS");
	}

	private static void dataFile(ClientGameTestContext ctx, TestSingleplayerContext world) {
		Set<MorphAbilities.Ability> abilities = ctx.computeOnClient(mc -> MorphAbilities.of(EntityTypes.PANDA, mc.level));
		check(abilities.equals(Set.of(MorphAbilities.Ability.CLIMB, MorphAbilities.Ability.NIGHT_VISION)),
			"panda: client abilities " + abilities);
		String clientName = ctx.computeOnClient(mc -> MorphPowers.name(EntityTypes.PANDA, mc.level));
		check("Test snowball".equals(clientName), "panda: client power name " + clientName);

		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			MorphPowers.Power power = MorphPowers.of(EntityTypes.PANDA);
			check(power != null && power.cooldown() == 7 && power.name().equals("Test snowball"), "panda: server power " + power);
			MorphState.unlock(p, EntityTypes.PANDA);
			check(MorphState.morph(p, EntityTypes.PANDA), "panda: morph refused");
			check(p.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION), "panda: no night vision");
			MorphPowers.clearCooldowns(p);
			MorphPowers.use(p);
			check(!p.level().getEntitiesOfClass(Snowball.class, p.getBoundingBox().inflate(4)).isEmpty(), "panda: no snowball thrown");
			check(p.getAttachedOrElse(MorphPowers.COOLDOWN, 0) == 7, "panda: cooldown " + p.getAttachedOrElse(MorphPowers.COOLDOWN, 0));

			Mob cow = EntityTypes.COW.create(p.level(), EntitySpawnReason.COMMAND);
			cow.setNoAi(true);
			cow.setPos(p.getX() + 1.5, p.getY(), p.getZ());
			p.level().addFreshEntity(cow);
			p.attack(cow);
			check(cow.isOnFire(), "panda: hit didn't set the cow on fire");
			cow.discard();
			MorphState.unmorph(p);
		});
		Morph.LOGGER.info("MORPH-TEST ok data file");
	}

	private static void events(ClientGameTestContext ctx, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer p = world.getConnection().getServerPlayer();
			SEEN.clear();
			MorphState.unlock(p, EntityTypes.GOAT);
			MorphState.unlock(p, EntityTypes.PIG);
			check(MorphState.morph(p, EntityTypes.PIG), "pig: morph refused");
			check(MorphState.morph(p, EntityTypes.GOAT), "goat: morph refused");
			check(MorphState.unmorph(p), "unmorph refused");
			check(SEEN.equals(List.of("unlock goat", "unlock pig", "morph self -> pig", "morph pig -> goat", "morph goat -> self")),
				"events seen: " + SEEN);

			refuseMorph = EntityTypes.GOAT;
			check(!MorphState.morph(p, EntityTypes.GOAT), "goat: morph went through a refusing listener");
			check(MorphState.current(p) == null, "goat: refused morph changed body to " + MorphState.current(p));
			check(MorphState.morph(p, EntityTypes.PIG), "pig: refused along with the goat");
			refuseMorph = null;
			MorphState.unmorph(p);

			refuseUnlock = EntityTypes.SHEEP;
			check(!MorphState.unlock(p, EntityTypes.SHEEP), "sheep: unlock went through a refusing listener");
			check(!MorphState.isUnlocked(p, EntityTypes.SHEEP), "sheep: unlocked anyway");
			refuseUnlock = null;
		});
		Morph.LOGGER.info("MORPH-TEST ok events");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			Morph.LOGGER.error("MORPH-TEST FAIL: {}", message);
			throw new AssertionError(message);
		}
	}
}

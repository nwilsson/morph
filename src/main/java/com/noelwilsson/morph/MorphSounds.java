package com.noelwilsson.morph;

import com.noelwilsson.morph.mixin.EntityInvoker;
import com.noelwilsson.morph.mixin.LivingEntityInvoker;
import com.noelwilsson.morph.mixin.MobAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A morphed player sounds like the mob: hurt, death, fall and step sounds, and now and then its idle sound. The mob's
 * own code picks each sound, asked through a template of the player's look (see PlayerMixin). Runs on both sides,
 * like vanilla: the client plays the player's own sounds, the server plays them to everyone else.
 */
public final class MorphSounds {
	/** A sound a template tried to play while its steps were being listened to. */
	private record Heard(SoundEvent sound, float volume, float pitch) {}

	/** The template whose sounds are being caught instead of played, per thread (the client and server both ask). */
	private static final ThreadLocal<@Nullable Entity> LISTENING = new ThreadLocal<>();
	private static final ThreadLocal<List<Heard>> HEARD = ThreadLocal.withInitial(ArrayList::new);
	/** Mob.ambientSoundTime for each morphed player: counts up until the next idle sound. */
	private static final Map<UUID, Integer> AMBIENT_TIME = new HashMap<>();

	private MorphSounds() {}

	/** The template that speaks for this player, or null if they aren't morphed. */
	public static @Nullable LivingEntity voice(Player player) {
		MorphVariant variant = MorphState.currentVariant(player);
		return variant == null ? null : MorphTemplates.get(variant, player.level());
	}

	public static @Nullable SoundEvent hurtSound(LivingEntity template, DamageSource source) {
		return ((LivingEntityInvoker) template).morph$getHurtSound(source);
	}

	public static @Nullable SoundEvent deathSound(LivingEntity template) {
		return ((LivingEntityInvoker) template).morph$getDeathSound();
	}

	public static float volume(LivingEntity template) {
		return ((LivingEntityInvoker) template).morph$getSoundVolume();
	}

	/**
	 * Plays the mob's footstep on this block from the player. Mobs pick steps in many ways (a zombie's own step, a
	 * horse's hooves on wood), so the template takes the step and its sounds are caught and replayed by the player.
	 */
	public static void step(Player player, LivingEntity template, BlockPos pos, BlockState state) {
		List<Heard> heard = HEARD.get();
		heard.clear();
		template.setPos(player.getX(), player.getY(), player.getZ());
		LISTENING.set(template);
		try {
			((EntityInvoker) template).morph$playStepSound(pos, state);
		} catch (RuntimeException e) {
			Morph.LOGGER.debug("Step sound failed for {}", template.getType(), e);
		} finally {
			LISTENING.remove();
		}
		for (Heard sound : List.copyOf(heard)) {
			player.playSound(sound.sound(), sound.volume(), sound.pitch());
		}
		heard.clear();
	}

	/** Called by EntityMixin for every Entity.playSound: true if it was a listened-to template's, so caught here. */
	public static boolean catchSound(Entity entity, SoundEvent sound, float volume, float pitch) {
		if (LISTENING.get() != entity) {
			return false;
		}
		HEARD.get().add(new Heard(sound, volume, pitch));
		return true;
	}

	/**
	 * Every server tick: the mob's idle sound at the mob's own rate (Mob.baseTick), heard by everyone including the
	 * player. Sneaking keeps a disguise quiet.
	 */
	public static void tick(ServerPlayer player) {
		LivingEntity template = voice(player);
		if (!(template instanceof Mob mob) || !player.isAlive() || player.isSpectator()) {
			AMBIENT_TIME.remove(player.getUUID());
			return;
		}
		int time = AMBIENT_TIME.getOrDefault(player.getUUID(), -mob.getAmbientSoundInterval());
		if (player.getRandom().nextInt(1000) < time++) {
			time = -mob.getAmbientSoundInterval();
			SoundEvent sound = player.isShiftKeyDown() ? null : ((MobAccessor) mob).morph$ambientSound();
			if (sound != null) {
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, player.getSoundSource(), volume(mob),
					mob.getVoicePitch());
			}
		}
		AMBIENT_TIME.put(player.getUUID(), time);
	}

	public static void forget(UUID player) {
		AMBIENT_TIME.remove(player);
	}
}

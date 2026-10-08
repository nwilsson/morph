package com.noelwilsson.morph;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Hooks for other mods, all on the server. The ALLOW events can stop an unlock or a change of body (any listener
 * returning false stops it); the AFTER events say it happened. A body is a {@link MorphVariant}, or null for the
 * player's own.
 */
public final class MorphEvents {
	/** Before a player unlocks a morph, by a kill or by /morph unlock. False stops it; the kill still counts. */
	public static final Event<AllowUnlock> ALLOW_UNLOCK = EventFactory.createArrayBacked(AllowUnlock.class, listeners -> (player, variant) -> {
		for (AllowUnlock listener : listeners) {
			if (!listener.allowUnlock(player, variant)) {
				return false;
			}
		}
		return true;
	});

	/** After a player unlocks a morph they didn't have. */
	public static final Event<AfterUnlock> AFTER_UNLOCK = EventFactory.createArrayBacked(AfterUnlock.class, listeners -> (player, variant) -> {
		for (AfterUnlock listener : listeners) {
			listener.afterUnlock(player, variant);
		}
	});

	/**
	 * Before a player changes body: into a mob, or back to themselves when {@code to} is null. False keeps the body
	 * they have. Not asked when Morphed takes a body away itself (the mob was blocked by a datapack, or the player died).
	 */
	public static final Event<AllowMorph> ALLOW_MORPH = EventFactory.createArrayBacked(AllowMorph.class, listeners -> (player, to) -> {
		for (AllowMorph listener : listeners) {
			if (!listener.allowMorph(player, to)) {
				return false;
			}
		}
		return true;
	});

	/** After a player changes body. Not fired on death, which drops the morph without a change of body. */
	public static final Event<AfterMorph> AFTER_MORPH = EventFactory.createArrayBacked(AfterMorph.class, listeners -> (player, from, to) -> {
		for (AfterMorph listener : listeners) {
			listener.afterMorph(player, from, to);
		}
	});

	private MorphEvents() {}

	@FunctionalInterface
	public interface AllowUnlock {
		boolean allowUnlock(ServerPlayer player, MorphVariant variant);
	}

	@FunctionalInterface
	public interface AfterUnlock {
		void afterUnlock(ServerPlayer player, MorphVariant variant);
	}

	@FunctionalInterface
	public interface AllowMorph {
		boolean allowMorph(ServerPlayer player, @Nullable MorphVariant to);
	}

	@FunctionalInterface
	public interface AfterMorph {
		void afterMorph(ServerPlayer player, @Nullable MorphVariant from, @Nullable MorphVariant to);
	}
}

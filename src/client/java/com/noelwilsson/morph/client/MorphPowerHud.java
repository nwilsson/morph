package com.noelwilsson.morph.client;

import com.noelwilsson.morph.MorphPowers;
import com.noelwilsson.morph.MorphState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/** Right of the hotbar: "[R] Fireballs" and a cooldown bar that fills as the power recharges. */
public final class MorphPowerHud {
	private static final int WIDTH = 84;

	private MorphPowerHud() {}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		Player player = minecraft.player;
		if (player == null || player.isSpectator()) {
			return;
		}
		MorphPowers.Power power = MorphPowers.of(MorphState.current(player));
		if (power == null) {
			return;
		}
		int x = graphics.guiWidth() / 2 + 98;
		int y = graphics.guiHeight() - 22;
		String key = "[" + MorphClient.USE_POWER.getTranslatedKeyMessage().getString() + "] ";
		long now = player.level().getGameTime();
		long readyAt = player.getAttachedOrElse(MorphPowers.READY_AT, 0L);
		int cooldown = player.getAttachedOrElse(MorphPowers.COOLDOWN, 0);
		boolean ready = now >= readyAt || cooldown <= 0;

		graphics.fill(x, y, x + WIDTH, y + 20, 0x90000000);
		graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(key + power.name(), WIDTH - 6), x + 3, y + 3, ready ? 0xFFFFFFFF : 0xFFA0A0A0);
		float charged = ready ? 1.0F : 1.0F - (readyAt - now - delta.getGameTimeDeltaPartialTick(false)) / cooldown;
		int barWidth = Math.round((WIDTH - 6) * Math.max(0.0F, Math.min(1.0F, charged)));
		graphics.fill(x + 3, y + 14, x + WIDTH - 3, y + 17, 0xFF303030);
		graphics.fill(x + 3, y + 14, x + 3 + barWidth, y + 17, ready ? 0xFF50D070 : 0xFFE0A030);
	}
}

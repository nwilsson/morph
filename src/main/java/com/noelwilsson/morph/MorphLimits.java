package com.noelwilsson.morph;

import java.util.List;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * morph:body_limits: the body limits what the player can do. Mobs without hands (anything not in #morph:has_hands)
 * can eat and drink but not use, place or wear anything else. Animals keep their diet: meat-eaters eat only meat and
 * fish, plant-eaters no meat or fish, and anything eats what it's bred with. Off by default; creative ignores it.
 */
public final class MorphLimits {
	/** Meat and fish an animal might eat, to tell meat-eaters (wolves, cats) from plant-eaters by asking the animal. */
	private static final List<Item> MEATS = List.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT,
		Items.COD, Items.SALMON);

	private MorphLimits() {}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> refuse(player, refusal(player, player.getItemInHand(hand))));
		// Using an item on a block (placing it, lighting a fire, bonemeal). Food falls through to the item use above.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			ItemStack stack = player.getItemInHand(hand);
			return stack.has(DataComponents.CONSUMABLE) ? InteractionResult.PASS : refuse(player, refusal(player, stack));
		});
	}

	private static InteractionResult refuse(Player player, @Nullable Component refusal) {
		if (refusal == null) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer server) {
			server.sendOverlayMessage(refusal);
		}
		return InteractionResult.FAIL;
	}

	/** Whether the rule is on and applies to this player now. Works on both sides. */
	private static boolean limited(Player player) {
		return MorphState.current(player) != null && !player.isCreative() && !player.isSpectator()
			&& MorphRules.clientRules(player.level()).bodyLimits();
	}

	public static boolean hasHands(EntityType<?> type) {
		return type.builtInRegistryHolder().is(MorphRules.HAS_HANDS);
	}

	/** Why the body can't use this item, or null if it can. */
	public static @Nullable Component refusal(Player player, ItemStack stack) {
		EntityType<?> type = MorphState.current(player);
		if (stack.isEmpty() || type == null || !limited(player)) {
			return null;
		}
		if (stack.has(DataComponents.CONSUMABLE)) {
			LivingEntity body = MorphTemplates.body(player);
			return !stack.has(DataComponents.FOOD) || eats(body, stack) ? null
				: Component.literal("You don't eat that as a ").append(type.getDescription());
		}
		return hasHands(type) ? null : Component.literal("You can't use that as a ").append(type.getDescription());
	}

	/**
	 * The animal's own food (what it's bred with) is always fine. Otherwise an animal that would take meat or fish is a
	 * meat-eater and eats only that; any other animal eats anything but. Mobs that aren't animals eat anything.
	 */
	public static boolean eats(@Nullable LivingEntity body, ItemStack food) {
		if (!(body instanceof Animal animal) || animal.isFood(food)) {
			return true;
		}
		boolean meat = food.is(ItemTags.MEAT) || food.is(ItemTags.FISHES);
		boolean meatEater = MEATS.stream().anyMatch(item -> animal.isFood(new ItemStack(item)));
		return meat == meatEater;
	}

	/** Every second: a body without hands takes off any armour, into the inventory or onto the ground. */
	public static void tick(ServerPlayer player) {
		EntityType<?> type = MorphState.current(player);
		if (type == null || !limited(player) || hasHands(type)) {
			return;
		}
		boolean tookOff = false;
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack worn = player.getItemBySlot(slot);
			if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && !worn.isEmpty()) {
				player.setItemSlot(slot, ItemStack.EMPTY);
				if (!player.getInventory().add(worn)) {
					player.spawnAtLocation(player.level(), worn, 1.0F);
				}
				tookOff = true;
			}
		}
		if (tookOff) {
			player.sendOverlayMessage(Component.literal("You can't wear armour as a ").append(type.getDescription()));
		}
	}
}

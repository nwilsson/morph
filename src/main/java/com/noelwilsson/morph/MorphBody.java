package com.noelwilsson.morph;

import com.noelwilsson.morph.mixin.MobAccessor;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * What players and the weather can do to a morphed body, as they could to the mob: shear a sheep (or a snow golem's
 * pumpkin, or a bogged's mushrooms), milk a cow or goat, fill a bowl from a mooshroom, charge a creeper with
 * lightning, saddle a horse and ride it. Another player does it by using the item on you; you do it to yourself by
 * sneaking and using it.
 */
public final class MorphBody {
	/**
	 * The saddle or harness the body wears, for mobs that take one (#can_equip_saddle, #can_equip_harness). Others
	 * can ride a player who wears one. Everyone sees it, so the disguise can draw it.
	 */
	public static final AttachmentType<ItemStack> TACK = AttachmentRegistry.<ItemStack>builder()
		.persistent(ItemStack.CODEC)
		.syncWith(ItemStack.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Morph.id("tack"));

	private MorphBody() {}

	public static void init() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			entity instanceof Player target && !player.isSpectator() ? useOn(player, target, hand) : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
			player.isShiftKeyDown() && !player.isSpectator() ? useOn(player, player, hand) : InteractionResult.PASS);
		// The saddle comes off with the body: dropped, like a dead horse's.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				dropTack(player);
			}
		});
	}

	/** The saddle or harness this player's body wears, or empty. */
	public static ItemStack tack(Player player) {
		return player.getAttachedOrElse(TACK, ItemStack.EMPTY);
	}

	/** Whether others can ride this player: a rideable morph with its saddle or harness on. */
	public static boolean canCarry(Player player) {
		EntityType<?> type = MorphState.current(player);
		return type != null && tackSlot(type, tack(player)) != null;
	}

	/**
	 * Where this item goes if it's tack the mob wears: a saddle on a horse, pig, strider or camel, a harness on a
	 * happy ghast. Asked of the item's own equippable rules, so modded saddles and mounts work too.
	 */
	public static @Nullable EquipmentSlot tackSlot(EntityType<?> type, ItemStack stack) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable == null || !equippable.canBeEquippedBy(type.builtInRegistryHolder())) {
			return null;
		}
		if (equippable.slot() == EquipmentSlot.SADDLE) {
			return EquipmentSlot.SADDLE;
		}
		return equippable.slot() == EquipmentSlot.BODY && stack.is(ItemTags.HARNESSES) ? EquipmentSlot.BODY : null;
	}

	/** After a change of body: tack the new body can't wear falls off, and riders get off a body that can't carry them. */
	public static void changedBody(ServerPlayer player) {
		EntityType<?> type = MorphState.current(player);
		if (type == null || tackSlot(type, tack(player)) == null) {
			dropTack(player);
		}
		if (!canCarry(player)) {
			player.ejectPassengers();
		}
	}

	private static void dropTack(ServerPlayer player) {
		ItemStack tack = player.removeAttached(TACK);
		if (tack != null && !tack.isEmpty()) {
			player.spawnAtLocation(player.level(), tack, 1.0F);
		}
	}

	/** Saddling, unsaddling and getting on. Null if the item isn't for that. */
	private static @Nullable InteractionResult tackOrRide(Player user, Player target, EntityType<?> type, ItemStack stack) {
		ItemStack tack = tack(target);
		if (tack.isEmpty()) {
			EquipmentSlot slot = tackSlot(type, stack);
			if (slot == null) {
				return null;
			}
			if (target instanceof ServerPlayer server) {
				server.setAttached(TACK, stack.copyWithCount(1));
				stack.consume(1, user);
				Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
				target.level().playSound(null, target, (equippable != null ? equippable.equipSound() : SoundEvents.HORSE_SADDLE).value(),
					SoundSource.PLAYERS, 1.0F, 1.0F);
			}
			return InteractionResult.SUCCESS;
		}
		// Shears take a saddle off, as they do a horse's.
		if (stack.is(Items.SHEARS)) {
			if (target instanceof ServerPlayer server) {
				Equippable equippable = tack.get(DataComponents.EQUIPPABLE);
				target.level().playSound(null, target, (equippable != null ? equippable.shearingSound() : SoundEvents.SADDLE_UNEQUIP).value(),
					SoundSource.PLAYERS, 1.0F, 1.0F);
				dropTack(server);
			}
			return InteractionResult.SUCCESS;
		}
		if (user != target && stack.isEmpty() && canCarry(target) && !target.isVehicle() && !user.isPassenger()) {
			if (!user.level().isClientSide()) {
				user.startRiding(target);
			}
			return InteractionResult.SUCCESS;
		}
		return null;
	}

	/**
	 * Hands the interaction to a copy of the target's body, standing where they stand, so the mob's own code does it
	 * (drops, sounds, filled buckets, worn-out shears), on both sides like vanilla. Then the body takes the copy's new
	 * look: a sheared sheep stays sheared until it eats grass.
	 */
	private static InteractionResult useOn(Player user, Player target, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		MorphVariant variant = MorphState.currentVariant(target);
		EntityType<?> type = variant == null ? null : variant.type();
		if (type == null || !target.isAlive()) {
			return InteractionResult.PASS;
		}
		InteractionResult tacked = tackOrRide(user, target, type, stack);
		if (tacked != null) {
			return tacked;
		}
		LivingEntity created = MorphVariant.create(type, variant.data(), target.level());
		if (!(created instanceof Mob body) || !handles(body, stack)) {
			return InteractionResult.PASS;
		}
		body.snapTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
		InteractionResult result = ((MobAccessor) body).morph$mobInteract(user, hand);
		if (result.consumesAction() && target instanceof ServerPlayer server) {
			MorphVariant look = MorphVariant.of(body);
			if (!look.equals(variant)) {
				MorphState.changeLook(server, look);
			}
		}
		return result;
	}

	/**
	 * The interactions that only change the mob or hand out an item. Others are left alone: a bucket would scoop up
	 * a fish-morphed player's body, food would breed it, and shearing a mooshroom turns it into a new cow.
	 */
	private static boolean handles(Mob body, ItemStack stack) {
		if (stack.is(Items.SHEARS)) {
			return body instanceof Shearable && !(body instanceof MushroomCow);
		}
		if (stack.is(Items.BUCKET)) {
			return body instanceof AbstractCow || body instanceof Goat;
		}
		return stack.is(Items.BOWL) && body instanceof MushroomCow;
	}

	/** Lightning charges a creeper-morphed player, like a real creeper, and unlocks the charged creeper. */
	public static void struckByLightning(ServerPlayer player) {
		MorphVariant variant = MorphState.currentVariant(player);
		EntityType<?> type = variant == null ? null : variant.type();
		if (type == null || !(MorphTemplates.get(variant, player.level()) instanceof Creeper creeper) || creeper.isPowered()) {
			return;
		}
		CompoundTag charged = variant.data().copy();
		charged.putBoolean("powered", true);
		MorphVariant look = MorphVariant.parse(type, charged, player.level());
		if (look == null) {
			return;
		}
		if (MorphState.unlock(player, look)) {
			Morph.announceUnlock(player, look);
		}
		MorphState.changeLook(player, look);
	}

	/** A sheep that eats grass grows its wool back (Mob.ate). Returns the body's new look, or null if it didn't change. */
	public static @Nullable MorphVariant ate(ServerPlayer player) {
		MorphVariant variant = MorphState.currentVariant(player);
		EntityType<?> type = variant == null ? null : variant.type();
		if (type == null || !(MorphVariant.create(type, variant.data(), player.level()) instanceof Mob body)) {
			return null;
		}
		body.ate();
		MorphVariant look = MorphVariant.of(body);
		if (look.equals(variant)) {
			return null;
		}
		MorphState.changeLook(player, look);
		return look;
	}
}

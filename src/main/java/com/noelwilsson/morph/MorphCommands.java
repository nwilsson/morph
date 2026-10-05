package com.noelwilsson.morph;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * /morph, /morph &lt;mob&gt; [nbt], /unmorph, and the op-only /morph unlock &lt;mob&gt; [nbt] for testing. The NBT picks
 * a look, e.g. /morph sheep {Color:14b}; without it you get the first look of that mob you unlocked.
 */
public final class MorphCommands {
	private static final DynamicCommandExceptionType NOT_UNLOCKED = new DynamicCommandExceptionType(
		type -> Component.literal("You haven't unlocked " + type + " yet. Kill one first."));
	private static final DynamicCommandExceptionType CANNOT_MORPH = new DynamicCommandExceptionType(
		type -> Component.literal("Can't morph into " + type + "."));

	private MorphCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
		dispatcher.register(Commands.literal("morph")
			.executes(MorphCommands::list)
			.then(Commands.literal("unlock")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("mob", ResourceArgument.resource(context, Registries.ENTITY_TYPE))
					.executes(ctx -> unlock(ctx, ResourceArgument.getSummonableEntityType(ctx, "mob").value(), null))
					.then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
						.executes(ctx -> unlock(ctx, ResourceArgument.getSummonableEntityType(ctx, "mob").value(),
							CompoundTagArgument.getCompoundTag(ctx, "nbt"))))))
			.then(Commands.argument("mob", IdentifierArgument.id())
				.suggests((ctx, builder) -> {
					Set<Identifier> types = new LinkedHashSet<>();
					ctx.getSource().getPlayerOrException().getAttachedOrElse(MorphState.UNLOCKED, List.<MorphVariant>of())
						.forEach(variant -> types.add(variant.id()));
					return SharedSuggestionProvider.suggestResource(types, builder);
				})
				.executes(ctx -> morph(ctx, null))
				.then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
					.suggests((ctx, builder) -> {
						EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(IdentifierArgument.getId(ctx, "mob")).orElse(null);
						if (type == null) {
							return builder.buildFuture();
						}
						return SharedSuggestionProvider.suggest(MorphState.unlocked(ctx.getSource().getPlayerOrException(), type).stream()
							.filter(variant -> !variant.isDefault()).map(MorphVariant::snbt), builder);
					})
					.executes(ctx -> morph(ctx, CompoundTagArgument.getCompoundTag(ctx, "nbt"))))));
		dispatcher.register(Commands.literal("unmorph").executes(ctx -> {
			MorphState.unmorph(ctx.getSource().getPlayerOrException());
			ctx.getSource().sendSuccess(() -> Component.literal("You're yourself again."), false);
			return 1;
		}));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		List<MorphVariant> unlocked = player.getAttachedOrElse(MorphState.UNLOCKED, List.of());
		String names = unlocked.isEmpty() ? "none yet. Kill a mob to unlock it." : String.join(", ", unlocked.stream().map(variant -> {
			String look = variant.describe(player.level());
			return variant.id().getPath() + (look.isEmpty() ? "" : " (" + look + ")");
		}).toList());
		ctx.getSource().sendSuccess(() -> Component.literal("Morphs: " + names), false);
		return unlocked.size();
	}

	private static int morph(CommandContext<CommandSourceStack> ctx, @Nullable CompoundTag nbt) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Identifier id = IdentifierArgument.getId(ctx, "mob");
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElseThrow(() -> CANNOT_MORPH.create(id));
		if (!MorphState.canMorphInto(type)) {
			throw CANNOT_MORPH.create(id);
		}
		if (nbt == null) {
			if (!MorphState.isUnlocked(player, type)) {
				throw NOT_UNLOCKED.create(id.getPath());
			}
			MorphState.morph(player, type);
		} else {
			MorphVariant variant = MorphVariant.parse(type, nbt, player.level());
			if (variant == null) {
				throw CANNOT_MORPH.create(id);
			}
			if (!MorphState.isUnlocked(player, variant)) {
				String look = variant.describe(player.level());
				throw NOT_UNLOCKED.create(look.isEmpty() ? id.getPath() : id.getPath() + " (" + look + ")");
			}
			MorphState.morph(player, variant);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("You are now a ").append(type.getDescription()).append("."), false);
		return 1;
	}

	private static int unlock(CommandContext<CommandSourceStack> ctx, EntityType<?> type, @Nullable CompoundTag nbt)
		throws CommandSyntaxException {
		if (!MorphState.canMorphInto(type)) {
			throw CANNOT_MORPH.create(BuiltInRegistries.ENTITY_TYPE.getKey(type));
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MorphVariant variant = nbt == null ? MorphVariant.of(type) : MorphVariant.parse(type, nbt, player.level());
		if (variant == null) {
			throw CANNOT_MORPH.create(BuiltInRegistries.ENTITY_TYPE.getKey(type));
		}
		MorphState.unlock(player, variant);
		String look = variant.describe(player.level());
		ctx.getSource().sendSuccess(() -> Component.literal("Unlocked ").append(type.getDescription())
			.append(look.isEmpty() ? "" : " (" + look + ")"), false);
		return 1;
	}
}

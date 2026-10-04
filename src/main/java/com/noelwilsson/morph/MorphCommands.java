package com.noelwilsson.morph;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

/** /morph, /morph &lt;mob&gt;, /unmorph, and the op-only /morph unlock &lt;mob&gt; for testing. */
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
					.executes(ctx -> unlock(ctx, ResourceArgument.getSummonableEntityType(ctx, "mob").value()))))
			.then(Commands.argument("mob", IdentifierArgument.id())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
					ctx.getSource().getPlayerOrException().getAttachedOrElse(MorphState.UNLOCKED, List.of()), builder))
				.executes(MorphCommands::morph)));
		dispatcher.register(Commands.literal("unmorph").executes(ctx -> {
			MorphState.unmorph(ctx.getSource().getPlayerOrException());
			ctx.getSource().sendSuccess(() -> Component.literal("You're yourself again."), false);
			return 1;
		}));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		List<Identifier> unlocked = ctx.getSource().getPlayerOrException().getAttachedOrElse(MorphState.UNLOCKED, List.of());
		String names = unlocked.isEmpty() ? "none yet. Kill a mob to unlock it." : String.join(", ", unlocked.stream().map(Identifier::getPath).toList());
		ctx.getSource().sendSuccess(() -> Component.literal("Morphs: " + names), false);
		return unlocked.size();
	}

	private static int morph(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Identifier id = IdentifierArgument.getId(ctx, "mob");
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElseThrow(() -> CANNOT_MORPH.create(id));
		if (!MorphState.canMorphInto(type)) {
			throw CANNOT_MORPH.create(id);
		}
		if (!MorphState.isUnlocked(player, type)) {
			throw NOT_UNLOCKED.create(id.getPath());
		}
		MorphState.morph(player, type);
		ctx.getSource().sendSuccess(() -> Component.literal("You are now a ").append(type.getDescription()).append("."), false);
		return 1;
	}

	private static int unlock(CommandContext<CommandSourceStack> ctx, EntityType<?> type) throws CommandSyntaxException {
		if (!MorphState.canMorphInto(type)) {
			throw CANNOT_MORPH.create(BuiltInRegistries.ENTITY_TYPE.getKey(type));
		}
		MorphState.unlock(ctx.getSource().getPlayerOrException(), type);
		ctx.getSource().sendSuccess(() -> Component.literal("Unlocked ").append(type.getDescription()), false);
		return 1;
	}
}

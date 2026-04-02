package com.hide;

import com.hide.visibility.HiddenPlayerService;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class HIDE implements ModInitializer {
	public static final String MOD_ID = "hide";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Set<String> HIDDEN_PLAYER_SYSTEM_MESSAGE_KEYS = Set.of(
		"multiplayer.player.joined",
		"multiplayer.player.joined.renamed",
		"multiplayer.player.left",
		"chat.type.advancement.task",
		"chat.type.advancement.goal",
		"chat.type.advancement.challenge"
	);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommand(dispatcher));

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> !HiddenPlayerService.isHidden(sender.getUUID()));
		ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, params) -> {
			if (source.getEntity() instanceof ServerPlayer player) {
				return !HiddenPlayerService.isHidden(player.getUUID());
			}
			return true;
		});
 		ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
			if (overlay) {
				return true;
			}
			return !isHiddenPlayerSystemMessage(server, message);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			HiddenPlayerService.onPlayerJoin(handler.getPlayer());
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			HiddenPlayerService.onPlayerRespawn(newPlayer);
		});

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			HiddenPlayerService.onPlayerChangeWorld(player);
		});

		ServerTickEvents.END_SERVER_TICK.register(HiddenPlayerService::onServerTick);

		LOGGER.info("Hide mod initialized");
	}

	private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hide")
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
			.then(Commands.argument("player", EntityArgument.player())
				.then(Commands.literal("enable").executes(context -> setHidden(context.getSource(), EntityArgument.getPlayer(context, "player"), true)))
				.then(Commands.literal("disable").executes(context -> setHidden(context.getSource(), EntityArgument.getPlayer(context, "player"), false)))));
	}

	private static int setHidden(CommandSourceStack source, ServerPlayer target, boolean hidden) {
		String targetName = target.getName().getString();
		if (hidden) {
			if (!HiddenPlayerService.hide(target)) {
				source.sendFailure(Component.literal(targetName + " is already hidden."));
				return 0;
			}
			source.sendSuccess(() -> Component.literal("Hidden " + targetName), true);
			return 1;
		}

		if (!HiddenPlayerService.unhide(target)) {
			source.sendFailure(Component.literal(targetName + " is not hidden."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Shown " + targetName), true);
		return 1;
	}

	private static boolean isHiddenPlayerSystemMessage(net.minecraft.server.MinecraftServer server, Component message) {
		ComponentContents contents = message.getContents();
		if (!(contents instanceof TranslatableContents translatable)) {
			return false;
		}

		if (!HIDDEN_PLAYER_SYSTEM_MESSAGE_KEYS.contains(translatable.getKey())) {
			return false;
		}

		Object[] args = translatable.getArgs();
		if (args.length == 0) {
			return false;
		}

		String playerToken = extractTextArg(args[0]);
		return playerToken != null && HiddenPlayerService.matchesHiddenPlayerName(server, playerToken);
	}

	private static String extractTextArg(Object arg) {
		if (arg instanceof Component component) {
			return component.getString();
		}
		if (arg instanceof String string) {
			return string;
		}
		return null;
	}
}


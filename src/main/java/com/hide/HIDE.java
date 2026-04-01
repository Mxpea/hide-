package com.hide;

import com.hide.visibility.HiddenPlayerService;
import com.hide.visibility.HideVisibilityManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HIDE implements ModInitializer {
	public static final String MOD_ID = "hide";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static int tickCounter = 0;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommand(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer joined = handler.getPlayer();
			HideVisibilityManager.applyRulesForViewer(joined);
			if (HiddenPlayerService.isHidden(joined.getUUID())) {
				HideVisibilityManager.applyRulesForTarget(joined);
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (HiddenPlayerService.isHidden(newPlayer.getUUID())) {
				HideVisibilityManager.applyRulesForTarget(newPlayer);
			}
			HideVisibilityManager.applyRulesForViewer(newPlayer);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;
			if (tickCounter >= 20) {
				tickCounter = 0;
				HideVisibilityManager.enforceHiddenPlayers(server);
			}
		});

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
			HideVisibilityManager.applyRulesForTarget(target);
			source.sendSuccess(() -> Component.literal("Hidden " + targetName), true);
			return 1;
		}

		if (!HiddenPlayerService.unhide(target)) {
			source.sendFailure(Component.literal(targetName + " is not hidden."));
			return 0;
		}

		PlayerList playerList = source.getServer().getPlayerList();
		for (ServerPlayer viewer : playerList.getPlayers()) {
			HideVisibilityManager.showToViewer(viewer, target);
		}
		source.sendSuccess(() -> Component.literal("Shown " + targetName), true);
		return 1;
	}
}
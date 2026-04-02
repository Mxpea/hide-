package com.hide;

import com.hide.visibility.HiddenPlayerService;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class HIDE implements ModInitializer {
	public static final String MOD_ID = "hide";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommand(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			HiddenPlayerService.onPlayerJoin(handler.getPlayer());
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			HiddenPlayerService.onPlayerDisconnect(handler.getPlayer());
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			HiddenPlayerService.onPlayerRespawn(newPlayer);
		});

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			HiddenPlayerService.onPlayerChangeWorld(player);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(HiddenPlayerService::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HiddenPlayerService.onServerStopping());

		ServerTickEvents.END_SERVER_TICK.register(HiddenPlayerService::onServerTick);

		LOGGER.info("Hide mod initialized");
	}

	private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hide")
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
			.then(Commands.argument("player", GameProfileArgument.gameProfile())
				.then(Commands.literal("enable").executes(context -> setHidden(context.getSource(), GameProfileArgument.getGameProfiles(context, "player"), true)))
				.then(Commands.literal("disable").executes(context -> setHidden(context.getSource(), GameProfileArgument.getGameProfiles(context, "player"), false)))));
	}

	private static int setHidden(CommandSourceStack source, Collection<NameAndId> profiles, boolean hidden) {
		int changed = 0;
		for (NameAndId profile : profiles) {
			boolean result = hidden
				? HiddenPlayerService.hide(source.getServer(), profile.id())
				: HiddenPlayerService.unhide(source.getServer(), profile.id());
			if (result) {
				changed++;
			}
		}

		if (changed == 0) {
			source.sendFailure(Component.literal(hidden ? "No players were newly hidden." : "No players were newly shown."));
			return 0;
		}

		int total = profiles.size();
		int changedCount = changed;
		source.sendSuccess(() -> Component.literal((hidden ? "Hidden " : "Shown ") + changedCount + "/" + total + " target(s)."), true);
		return changed;
	}
}


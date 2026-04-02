package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
public abstract class PlayerListAnnouncementMixin {
	@Redirect(
		method = "placeNewPlayer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
		)
	)
	private void hide$suppressJoinAnnouncement(PlayerList instance, Component message, boolean overlay) {
		if (!hide$isHiddenJoinMessage(instance, message)) {
			instance.broadcastSystemMessage(message, overlay);
		}
	}

	@Unique
	private static boolean hide$isHiddenJoinMessage(PlayerList playerList, Component message) {
		ComponentContents contents = message.getContents();
		if (!(contents instanceof TranslatableContents translatable)) {
			return false;
		}

		String key = translatable.getKey();
		if (!"multiplayer.player.joined".equals(key) && !"multiplayer.player.joined.renamed".equals(key)) {
			return false;
		}

		Object[] args = translatable.getArgs();
		if (args.length == 0) {
			return false;
		}

		String playerToken = hide$extractTextArg(args[0]);
		return playerToken != null && HiddenPlayerService.matchesHiddenPlayerName(playerList.getServer(), playerToken);
	}

	@Unique
	private static String hide$extractTextArg(Object arg) {
		if (arg instanceof Component component) {
			return component.getString();
		}
		if (arg instanceof String string) {
			return string;
		}
		return null;
	}
}


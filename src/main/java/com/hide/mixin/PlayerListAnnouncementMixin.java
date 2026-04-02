package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
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
	private void hide$suppressJoinAnnouncement(PlayerList instance, Component message, boolean overlay, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
		if (!HiddenPlayerService.isHidden(player.getUUID())) {
			instance.broadcastSystemMessage(message, overlay);
		}
	}
}


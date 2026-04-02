package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerAdvancementBroadcastMixin {
	@Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
	private void hide$suppressHiddenPlayerAdvancements(Component message, boolean overlay, CallbackInfo ci) {
		if (overlay || !HiddenPlayerService.shouldSuppressCurrentAdvancementBroadcast()) {
			return;
		}
		if (hide$isAdvancementMessage(message)) {
			ci.cancel();
		}
	}

	@Unique
	private static boolean hide$isAdvancementMessage(Component message) {
		ComponentContents contents = message.getContents();
		if (!(contents instanceof TranslatableContents translatable)) {
			return false;
		}
		String key = translatable.getKey();
		return "chat.type.advancement.task".equals(key)
			|| "chat.type.advancement.goal".equals(key)
			|| "chat.type.advancement.challenge".equals(key);
	}
}


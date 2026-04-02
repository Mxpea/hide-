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

import java.util.Set;

@Mixin(PlayerList.class)
public abstract class PlayerAdvancementBroadcastMixin {
	@Unique
	private static final Set<String> ADVANCEMENT_KEYS = Set.of(
		"chat.type.advancement.task",
		"chat.type.advancement.goal",
		"chat.type.advancement.challenge"
	);

	@Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
	private void hide$suppressHiddenPlayerAdvancements(Component message, boolean overlay, CallbackInfo ci) {
		if (overlay) {
			return;
		}

		ComponentContents contents = message.getContents();
		if (!(contents instanceof TranslatableContents translatable)) {
			return;
		}

		if (!ADVANCEMENT_KEYS.contains(translatable.getKey())) {
			return;
		}

		Object[] args = translatable.getArgs();
		if (args.length == 0) {
			return;
		}

		String playerToken = extractTextArg(args[0]);
		if (playerToken == null) {
			return;
		}

		PlayerList self = (PlayerList) (Object) this;
		if (HiddenPlayerService.matchesHiddenPlayerName(self.getServer(), playerToken)) {
			ci.cancel();
		}
	}

	@Unique
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


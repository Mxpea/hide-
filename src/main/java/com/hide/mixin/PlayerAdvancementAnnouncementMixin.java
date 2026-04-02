package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementAnnouncementMixin {
	@Shadow
	private ServerPlayer player;

	@Inject(method = "lambda$award$2", at = @At("HEAD"), cancellable = true)
	private void hide$suppressAdvancementAnnouncement(AdvancementHolder advancement, DisplayInfo displayInfo, CallbackInfo ci) {
		if (HiddenPlayerService.isHidden(this.player.getUUID())) {
			ci.cancel();
		}
	}
}


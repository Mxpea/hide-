package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementContextMixin {
	@Shadow
	private ServerPlayer player;

	@Inject(method = "award", at = @At("HEAD"))
	private void hide$pushAdvancementContext(CallbackInfoReturnable<Boolean> cir) {
		HiddenPlayerService.beginAdvancementBroadcast(this.player.getUUID());
	}

	@Inject(method = "award", at = @At("RETURN"))
	private void hide$clearAdvancementContext(CallbackInfoReturnable<Boolean> cir) {
		HiddenPlayerService.endAdvancementBroadcast();
	}
}


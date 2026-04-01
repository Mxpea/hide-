package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityVisibilityMixin {
	@Shadow
	@Final
	Entity entity;

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void hide$preventTrackingHiddenPlayers(ServerPlayer player, CallbackInfo ci) {
		if (this.entity instanceof ServerPlayer hiddenTarget
			&& HiddenPlayerService.isHidden(hiddenTarget.getUUID())
			&& player != hiddenTarget) {
			ci.cancel();
		}
	}
}

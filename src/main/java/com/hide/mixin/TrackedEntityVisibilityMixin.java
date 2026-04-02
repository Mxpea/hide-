package com.hide.mixin;

import com.hide.visibility.HiddenPlayerService;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityVisibilityMixin {
	@Shadow
	@Final
	Entity entity;

	@Redirect(
		method = "updatePlayer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerEntity;addPairing(Lnet/minecraft/server/level/ServerPlayer;)V"
		)
	)
	private void hide$skipPairingForHiddenTargets(ServerEntity serverEntity, ServerPlayer viewer) {
		if (this.entity instanceof ServerPlayer hiddenTarget
			&& HiddenPlayerService.isHidden(hiddenTarget.getUUID())
			&& viewer != hiddenTarget) {
			return;
		}

		serverEntity.addPairing(viewer);
	}
}

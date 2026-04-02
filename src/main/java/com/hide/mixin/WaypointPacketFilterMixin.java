package com.hide.mixin;

import com.hide.visibility.HideVisibilityManager;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class WaypointPacketFilterMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void hide$filterWaypointPacket(Packet<?> packet, CallbackInfo ci) {
		if (hide$shouldCancel(packet)) {
			ci.cancel();
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
	private void hide$filterWaypointPacketWithListener(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		if (hide$shouldCancel(packet)) {
			ci.cancel();
		}
	}

	@Unique
	private boolean hide$shouldCancel(Packet<?> packet) {

		if (!(packet instanceof ClientboundTrackedWaypointPacket waypointPacket)) {
			return false;
		}

		if (!((Object) this instanceof ServerGamePacketListenerImpl gameHandler)) {
			return false;
		}

		ServerPlayer viewer = gameHandler.player;
		return viewer != null && HideVisibilityManager.shouldHideWaypointForViewer(viewer, waypointPacket);
	}
}


package com.hide.visibility;

import com.hide.mixin.ChunkMapAccessor;
import com.hide.mixin.TrackedEntityAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import java.util.List;

public final class HideVisibilityManager {
	private HideVisibilityManager() {
	}

	public static void applyRulesForTarget(ServerPlayer target) {
		PlayerList playerList = target.level().getServer().getPlayerList();
		for (ServerPlayer viewer : playerList.getPlayers()) {
			hideFromViewer(viewer, target);
		}
	}

	public static void applyRulesForViewer(ServerPlayer viewer) {
		PlayerList playerList = viewer.level().getServer().getPlayerList();
		for (ServerPlayer target : playerList.getPlayers()) {
			if (HiddenPlayerService.isHidden(target.getUUID())) {
				hideFromViewer(viewer, target);
			}
		}
	}

	public static void hideFromViewer(ServerPlayer viewer, ServerPlayer target) {
		viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
		if (viewer == target) {
			return;
		}

		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		if (trackedEntity != null) {
			trackedEntity.hide$removePlayer(viewer);
		}
	}

	public static void showToViewer(ServerPlayer viewer, ServerPlayer target) {
		viewer.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, target));
		if (viewer == target) {
			return;
		}

		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		if (trackedEntity != null) {
			trackedEntity.hide$updatePlayer(viewer);
		}
	}

	private static TrackedEntityAccessor getTrackedEntity(ServerPlayer target) {
		ChunkMap chunkMap = target.level().getChunkSource().chunkMap;
		Int2ObjectMap<Object> entityMap = ((ChunkMapAccessor) chunkMap).hide$getEntityMap();
		Object trackedEntity = entityMap.get(target.getId());
		if (trackedEntity instanceof TrackedEntityAccessor accessor) {
			return accessor;
		}
		return null;
	}

	public static void enforceHiddenPlayers(MinecraftServer server) {
		PlayerList playerList = server.getPlayerList();
		for (var hiddenUuid : HiddenPlayerService.getHiddenPlayersSnapshot()) {
			ServerPlayer target = playerList.getPlayer(hiddenUuid);
			if (target == null) {
				continue;
			}

			for (ServerPlayer viewer : playerList.getPlayers()) {
				hideFromViewer(viewer, target);
			}
		}
	}
}


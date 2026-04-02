package com.hide.visibility;

import com.hide.mixin.ChunkMapAccessor;
import com.hide.mixin.TrackedEntityAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class HideVisibilityManager {
	private HideVisibilityManager() {
	}

	public static void applyHideTransition(PlayerList playerList, ServerPlayer target) {
		target.level().getWaypointManager().untrackWaypoint(target);
		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		for (ServerPlayer viewer : playerList.getPlayers()) {
			hideFromViewer(viewer, target, trackedEntity);
		}
	}

	public static void applyShowTransition(PlayerList playerList, ServerPlayer target) {
		target.level().getWaypointManager().trackWaypoint(target);
		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		for (ServerPlayer viewer : playerList.getPlayers()) {
			showToViewer(viewer, target, trackedEntity);
		}
	}

	public static void applyRulesForViewer(ServerPlayer viewer) {
		PlayerList playerList = viewer.level().getServer().getPlayerList();
		for (ServerPlayer target : playerList.getPlayers()) {
			if (HiddenPlayerService.isHidden(target.getUUID())) {
				hideFromViewer(viewer, target, getTrackedEntity(target));
			}
		}
	}

	public static void hideFromViewer(ServerPlayer viewer, ServerPlayer target) {
		hideFromViewer(viewer, target, getTrackedEntity(target));
	}

	private static void hideFromViewer(ServerPlayer viewer, ServerPlayer target, TrackedEntityAccessor trackedEntity) {
		if (viewer == target) {
			return;
		}

		viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
		if (trackedEntity != null) {
			trackedEntity.hide$removePlayer(viewer);
		}
	}

	public static void showToViewer(ServerPlayer viewer, ServerPlayer target) {
		showToViewer(viewer, target, getTrackedEntity(target));
	}

	private static void showToViewer(ServerPlayer viewer, ServerPlayer target, TrackedEntityAccessor trackedEntity) {
		if (viewer == target) {
			return;
		}

		viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
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

	public static boolean shouldHideWaypointForViewer(ServerPlayer viewer, ClientboundTrackedWaypointPacket packet) {
		if (packet.waypoint() == null || packet.waypoint().id() == null) {
			return false;
		}

		Optional<UUID> target = packet.waypoint().id().left();
		if (target.isEmpty()) {
			return false;
		}

		UUID targetUuid = target.get();
		if (!HiddenPlayerService.isHidden(targetUuid) || viewer.getUUID().equals(targetUuid)) {
			return false;
		}

		// Allow waypoint removal packets so stale locator entries can be cleared on clients.
		return !"UNTRACK".equals(String.valueOf(packet.operation()));
	}

}

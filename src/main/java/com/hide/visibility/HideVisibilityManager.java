package com.hide.visibility;

import com.hide.mixin.ChunkMapAccessor;
import com.hide.mixin.TrackedEntityAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public final class HideVisibilityManager {
	private static final ThreadLocal<Integer> WAYPOINT_FILTER_BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);

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
		runBypassingWaypointFilter(() -> viewer.connection.send(ClientboundTrackedWaypointPacket.removeWaypoint(target.getUUID())));
		if (viewer == target) {
			return;
		}

		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		if (trackedEntity != null) {
			trackedEntity.hide$removePlayer(viewer);
		}
	}

	public static void showToViewer(ServerPlayer viewer, ServerPlayer target) {
		// Rebuild player-list state in the same way a join would, so tab restoration is reliable.
		viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
		viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
		if (viewer == target) {
			viewer.level().getWaypointManager().updatePlayer(viewer);
			return;
		}

		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		if (trackedEntity != null) {
			trackedEntity.hide$updatePlayer(viewer);
		}
		viewer.level().getWaypointManager().updatePlayer(viewer);
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

	public static boolean shouldHideWaypointForViewer(ServerPlayer viewer, ClientboundTrackedWaypointPacket packet) {
		Optional<UUID> target = packet.waypoint().id().left();
		if (target.isEmpty()) {
			return false;
		}

		UUID targetUuid = target.get();
		return HiddenPlayerService.isHidden(targetUuid) && !viewer.getUUID().equals(targetUuid);
	}

	public static boolean isWaypointFilterBypassed() {
		return WAYPOINT_FILTER_BYPASS_DEPTH.get() > 0;
	}

	private static void runBypassingWaypointFilter(Runnable task) {
		WAYPOINT_FILTER_BYPASS_DEPTH.set(WAYPOINT_FILTER_BYPASS_DEPTH.get() + 1);
		try {
			task.run();
		} finally {
			WAYPOINT_FILTER_BYPASS_DEPTH.set(WAYPOINT_FILTER_BYPASS_DEPTH.get() - 1);
		}
	}
}

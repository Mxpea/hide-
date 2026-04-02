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
	private static final ThreadLocal<Integer> WAYPOINT_FILTER_BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);

	private HideVisibilityManager() {
	}

	public static void applyHideTransition(PlayerList playerList, ServerPlayer target) {
		for (ServerPlayer viewer : playerList.getPlayers()) {
			hideFromViewer(viewer, target);
		}
	}

	public static void applyShowTransition(PlayerList playerList, ServerPlayer target) {
		for (ServerPlayer viewer : playerList.getPlayers()) {
			showToViewer(viewer, target);
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
		if (viewer == target) {
			return;
		}

		viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
		// Resync waypoints instead of sending direct remove packets, which are fragile with some client stacks.
		viewer.level().getWaypointManager().updatePlayer(viewer);

		TrackedEntityAccessor trackedEntity = getTrackedEntity(target);
		if (trackedEntity != null) {
			trackedEntity.hide$removePlayer(viewer);
		}
	}

	public static void showToViewer(ServerPlayer viewer, ServerPlayer target) {
		if (viewer == target) {
			viewer.level().getWaypointManager().updatePlayer(viewer);
			return;
		}

		viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
		viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));

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

	public static boolean shouldHideWaypointForViewer(ServerPlayer viewer, ClientboundTrackedWaypointPacket packet) {
		if (packet.waypoint() == null || packet.waypoint().id() == null) {
			return false;
		}

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

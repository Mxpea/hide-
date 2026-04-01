package com.hide.visibility;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HiddenPlayerService {
	private static final Set<UUID> HIDDEN_PLAYERS = ConcurrentHashMap.newKeySet();

	private HiddenPlayerService() {
	}

	public static boolean hide(ServerPlayer player) {
		return HIDDEN_PLAYERS.add(player.getUUID());
	}

	public static boolean unhide(ServerPlayer player) {
		return HIDDEN_PLAYERS.remove(player.getUUID());
	}

	public static boolean isHidden(UUID uuid) {
		return HIDDEN_PLAYERS.contains(uuid);
	}

	public static Set<UUID> getHiddenPlayersSnapshot() {
		return Set.copyOf(HIDDEN_PLAYERS);
	}

	static Set<UUID> hiddenPlayersForTests() {
		return HIDDEN_PLAYERS;
	}
}

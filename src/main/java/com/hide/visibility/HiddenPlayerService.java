package com.hide.visibility;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

public final class HiddenPlayerService {
	private static final Set<UUID> HIDDEN_PLAYERS = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, HiddenSession> SESSIONS = new ConcurrentHashMap<>();
	private static final NavigableMap<Long, Queue<ScheduledTask>> SCHEDULED_TASKS = new ConcurrentSkipListMap<>();

	private static final long RESYNC_DELAY_SHORT = 2;
	private static final long RESYNC_DELAY_LONG = 5;
	private static final long WORLD_CHANGE_COOLDOWN = 6;
	private static final int ENFORCE_INTERVAL_TICKS = 100;
	private static final String PERSIST_FILE = "hide-hidden-players.txt";

	private static long currentTick = 0;
	private static int enforceCounter = 0;
	private static Path persistPath;

	private HiddenPlayerService() {
	}

	public static synchronized void onServerStarted(MinecraftServer server) {
		persistPath = server.getWorldPath(LevelResource.ROOT).resolve(PERSIST_FILE);
		currentTick = 0;
		enforceCounter = 0;
		SESSIONS.clear();
		SCHEDULED_TASKS.clear();
		loadHiddenPlayers();
	}

	public static synchronized void onServerStopping() {
		saveHiddenPlayers();
		SESSIONS.clear();
		SCHEDULED_TASKS.clear();
		HIDDEN_PLAYERS.clear();
		persistPath = null;
	}

	public static boolean hide(ServerPlayer player) {
		UUID playerId = player.getUUID();
		if (!HIDDEN_PLAYERS.add(playerId)) {
			return false;
		}

		int epoch = nextEpoch(playerId);
		scheduleTask(playerId, epoch, TaskType.APPLY_HIDE, 1);
		scheduleTask(playerId, epoch, TaskType.APPLY_HIDE, 3);
		saveHiddenPlayers();
		return true;
	}

	public static boolean unhide(ServerPlayer player) {
		UUID playerId = player.getUUID();
		if (!HIDDEN_PLAYERS.remove(playerId)) {
			return false;
		}

		int epoch = nextEpoch(playerId);
		scheduleTask(playerId, epoch, TaskType.APPLY_SHOW, 1);
		scheduleTask(playerId, epoch, TaskType.APPLY_SHOW, 3);
		saveHiddenPlayers();
		return true;
	}

	public static void onPlayerJoin(ServerPlayer player) {
		scheduleLifecycleResync(player, false);
	}

	public static void onPlayerRespawn(ServerPlayer player) {
		scheduleLifecycleResync(player, true);
	}

	public static void onPlayerChangeWorld(ServerPlayer player) {
		scheduleWorldChangeResync(player);
	}

	public static void onPlayerDisconnect(ServerPlayer player) {
		UUID playerId = player.getUUID();
		SESSIONS.remove(playerId);
		pruneScheduledTasksFor(playerId);
	}

	public static void onServerTick(MinecraftServer server) {
		currentTick++;
		executeDueTasks(server.getPlayerList());

		enforceCounter++;
		if (enforceCounter >= ENFORCE_INTERVAL_TICKS) {
			enforceCounter = 0;
			enforceHiddenPlayers(server.getPlayerList());
		}
	}

	public static boolean isHidden(UUID uuid) {
		return HIDDEN_PLAYERS.contains(uuid);
	}

	public static Set<UUID> getHiddenPlayersSnapshot() {
		return Set.copyOf(HIDDEN_PLAYERS);
	}

	public static boolean matchesHiddenPlayerName(MinecraftServer server, String messagePlayerName) {
		for (UUID hiddenPlayer : HIDDEN_PLAYERS) {
			ServerPlayer player = server.getPlayerList().getPlayer(hiddenPlayer);
			if (player == null) {
				continue;
			}

			if (messagePlayerName.equals(player.getName().getString())
				|| messagePlayerName.equals(player.getDisplayName().getString())) {
				return true;
			}
		}

		return false;
	}


	static Set<UUID> hiddenPlayersForTests() {
		return HIDDEN_PLAYERS;
	}

	private static void scheduleLifecycleResync(ServerPlayer player, boolean addCooldown) {
		UUID playerId = player.getUUID();
		HiddenSession session = getSession(playerId);
		int epoch = session.nextEpoch();
		if (addCooldown) {
			session.updateCooldownUntil(Math.max(session.cooldownUntilTick(), currentTick + WORLD_CHANGE_COOLDOWN));
		}

		scheduleTask(playerId, epoch, TaskType.RESYNC_LIFECYCLE, RESYNC_DELAY_SHORT);
		scheduleTask(playerId, epoch, TaskType.RESYNC_LIFECYCLE, RESYNC_DELAY_LONG);
	}

	private static void scheduleWorldChangeResync(ServerPlayer player) {
		UUID playerId = player.getUUID();
		int epoch = getSession(playerId).nextEpoch();

		// World transfer is timing-sensitive; apply immediately and with retries.
		scheduleTask(playerId, epoch, TaskType.RESYNC_LIFECYCLE, 1);
		scheduleTask(playerId, epoch, TaskType.RESYNC_LIFECYCLE, 3);
		scheduleTask(playerId, epoch, TaskType.RESYNC_LIFECYCLE, 8);
	}

	private static void scheduleTask(UUID playerId, int epoch, TaskType type, long delayTicks) {
		long runTick = currentTick + Math.max(1, delayTicks);
		SCHEDULED_TASKS.computeIfAbsent(runTick, ignored -> new ConcurrentLinkedQueue<>())
			.add(new ScheduledTask(playerId, epoch, type));
	}

	private static void executeDueTasks(PlayerList playerList) {
		while (true) {
			Map.Entry<Long, Queue<ScheduledTask>> entry = SCHEDULED_TASKS.firstEntry();
			if (entry == null || entry.getKey() > currentTick) {
				return;
			}

			Queue<ScheduledTask> tasks = SCHEDULED_TASKS.remove(entry.getKey());
			if (tasks == null) {
				continue;
			}

			for (ScheduledTask task : tasks) {
				executeTask(playerList, task);
			}
		}
	}

	private static void executeTask(PlayerList playerList, ScheduledTask task) {
		HiddenSession session = getSession(task.playerId);
		if (session.epoch() != task.epoch) {
			return;
		}

		ServerPlayer target = playerList.getPlayer(task.playerId);
		if (target == null) {
			return;
		}

		if (task.type == TaskType.RESYNC_LIFECYCLE && currentTick < session.cooldownUntilTick()) {
			scheduleTask(task.playerId, task.epoch, task.type, session.cooldownUntilTick() - currentTick);
			return;
		}

		switch (task.type) {
			case APPLY_HIDE -> HideVisibilityManager.applyHideTransition(playerList, target);
			case APPLY_SHOW -> HideVisibilityManager.applyShowTransition(playerList, target);
			case RESYNC_LIFECYCLE -> {
				if (HIDDEN_PLAYERS.contains(task.playerId)) {
					HideVisibilityManager.applyHideTransition(playerList, target);
				}
				HideVisibilityManager.applyRulesForViewer(target);
			}
		}
	}

	private static void enforceHiddenPlayers(PlayerList playerList) {
		for (UUID hiddenPlayer : HIDDEN_PLAYERS) {
			ServerPlayer target = playerList.getPlayer(hiddenPlayer);
			if (target == null) {
				continue;
			}

			HiddenSession session = getSession(hiddenPlayer);
			if (currentTick < session.cooldownUntilTick()) {
				continue;
			}

			HideVisibilityManager.applyHideTransition(playerList, target);
		}
	}

	private static HiddenSession getSession(UUID playerId) {
		return SESSIONS.computeIfAbsent(playerId, ignored -> new HiddenSession());
	}

	private static int nextEpoch(UUID playerId) {
		return getSession(playerId).nextEpoch();
	}

	private static void pruneScheduledTasksFor(UUID playerId) {
		for (Map.Entry<Long, Queue<ScheduledTask>> entry : SCHEDULED_TASKS.entrySet()) {
			Queue<ScheduledTask> tasks = entry.getValue();
			tasks.removeIf(task -> task.playerId.equals(playerId));
			if (tasks.isEmpty()) {
				SCHEDULED_TASKS.remove(entry.getKey(), tasks);
			}
		}
	}

	private static synchronized void loadHiddenPlayers() {
		HIDDEN_PLAYERS.clear();
		if (persistPath == null || !Files.exists(persistPath)) {
			return;
		}

		try {
			for (String line : Files.readAllLines(persistPath, StandardCharsets.UTF_8)) {
				String raw = line.trim();
				if (raw.isEmpty()) {
					continue;
				}

				try {
					HIDDEN_PLAYERS.add(UUID.fromString(raw));
				} catch (IllegalArgumentException ignored) {
					// Ignore malformed UUID lines to keep startup resilient.
				}
			}
		} catch (IOException ignored) {
			// Ignore persistence read errors and continue with in-memory state.
		}
	}

	private static synchronized void saveHiddenPlayers() {
		if (persistPath == null) {
			return;
		}

		try {
			Path parent = persistPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			List<String> lines = HIDDEN_PLAYERS.stream()
				.map(UUID::toString)
				.sorted()
				.collect(Collectors.toList());
			Files.write(persistPath, lines, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Ignore persistence write errors and keep runtime behavior unaffected.
		}
	}

	private enum TaskType {
		APPLY_HIDE,
		APPLY_SHOW,
		RESYNC_LIFECYCLE
	}

	private record ScheduledTask(UUID playerId, int epoch, TaskType type) {
	}

	private static final class HiddenSession {
		private int epoch = 0;
		private long cooldownUntilTick = 0;

		synchronized int nextEpoch() {
			epoch++;
			return epoch;
		}

		synchronized int epoch() {
			return epoch;
		}

		synchronized long cooldownUntilTick() {
			return cooldownUntilTick;
		}

		synchronized void updateCooldownUntil(long tick) {
			cooldownUntilTick = tick;
		}
	}
}

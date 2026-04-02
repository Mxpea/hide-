package com.hide.visibility;

import com.hide.HIDE;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class HiddenPlayerService {
	private static final Set<UUID> HIDDEN_PLAYERS = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, HiddenSession> SESSIONS = new HashMap<>();
	private static final NavigableMap<Long, List<ScheduledTask>> SCHEDULED_TASKS = new TreeMap<>();
	private static final Object TASK_LOCK = new Object();

	private static final long RESYNC_DELAY_SHORT = 2;
	private static final long RESYNC_DELAY_LONG = 5;
	private static final long WORLD_CHANGE_COOLDOWN = 6;
	private static final int ENFORCE_INTERVAL_TICKS = 100;
	private static final String PERSIST_FILE_NAME = "hide-hidden-players.txt";

	private static long currentTick = 0;
	private static int enforceCounter = 0;
	private static Path persistPath;
	private static final ThreadLocal<UUID> CURRENT_ADVANCEMENT_PLAYER = new ThreadLocal<>();
	private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "hide-persistence");
		thread.setDaemon(true);
		return thread;
	});

	private HiddenPlayerService() {
	}

	public static synchronized void onServerStarted(MinecraftServer server) {
		persistPath = server.getWorldPath(LevelResource.ROOT).resolve(PERSIST_FILE_NAME);
		currentTick = 0;
		enforceCounter = 0;
		synchronized (TASK_LOCK) {
			SESSIONS.clear();
			SCHEDULED_TASKS.clear();
		}
		loadHiddenPlayers();
	}

	public static synchronized void onServerStopping() {
		saveHiddenPlayersSync();
		synchronized (TASK_LOCK) {
			SESSIONS.clear();
			SCHEDULED_TASKS.clear();
		}
		HIDDEN_PLAYERS.clear();
		persistPath = null;
	}

	public static boolean hide(MinecraftServer server, UUID playerId) {
		if (!HIDDEN_PLAYERS.add(playerId)) {
			return false;
		}

		int epoch;
		synchronized (TASK_LOCK) {
			epoch = nextEpochLocked(playerId);
			scheduleIfOnlineLocked(server, playerId, epoch, TaskType.APPLY_HIDE, 1);
			scheduleIfOnlineLocked(server, playerId, epoch, TaskType.APPLY_HIDE, 3);
		}
		saveHiddenPlayersAsync();
		return true;
	}


	public static boolean unhide(MinecraftServer server, UUID playerId) {
		if (!HIDDEN_PLAYERS.remove(playerId)) {
			return false;
		}

		int epoch;
		synchronized (TASK_LOCK) {
			epoch = nextEpochLocked(playerId);
			scheduleIfOnlineLocked(server, playerId, epoch, TaskType.APPLY_SHOW, 1);
			scheduleIfOnlineLocked(server, playerId, epoch, TaskType.APPLY_SHOW, 3);
		}
		saveHiddenPlayersAsync();
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
		synchronized (TASK_LOCK) {
			SESSIONS.remove(playerId);
			pruneScheduledTasksForLocked(playerId);
		}
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

	public static void beginAdvancementBroadcast(UUID playerId) {
		CURRENT_ADVANCEMENT_PLAYER.set(playerId);
	}

	public static void endAdvancementBroadcast() {
		CURRENT_ADVANCEMENT_PLAYER.remove();
	}

	public static boolean shouldSuppressCurrentAdvancementBroadcast() {
		UUID playerId = CURRENT_ADVANCEMENT_PLAYER.get();
		return playerId != null && HIDDEN_PLAYERS.contains(playerId);
	}

	static Set<UUID> hiddenPlayersForTests() {
		return HIDDEN_PLAYERS;
	}

	private static void scheduleLifecycleResync(ServerPlayer player, boolean addCooldown) {
		UUID playerId = player.getUUID();
		synchronized (TASK_LOCK) {
			HiddenSession session = getSessionLocked(playerId);
			int epoch = session.nextEpoch();
			if (addCooldown) {
				session.updateCooldownUntil(Math.max(session.cooldownUntilTick(), currentTick + WORLD_CHANGE_COOLDOWN));
			}

			scheduleTaskLocked(playerId, epoch, TaskType.RESYNC_LIFECYCLE, RESYNC_DELAY_SHORT);
			scheduleTaskLocked(playerId, epoch, TaskType.RESYNC_LIFECYCLE, RESYNC_DELAY_LONG);
		}
	}

	private static void scheduleWorldChangeResync(ServerPlayer player) {
		UUID playerId = player.getUUID();
		synchronized (TASK_LOCK) {
			int epoch = nextEpochLocked(playerId);
			scheduleTaskLocked(playerId, epoch, TaskType.RESYNC_LIFECYCLE, 1);
			scheduleTaskLocked(playerId, epoch, TaskType.RESYNC_LIFECYCLE, 3);
			scheduleTaskLocked(playerId, epoch, TaskType.RESYNC_LIFECYCLE, 8);
		}
	}

	private static void scheduleTaskLocked(UUID playerId, int epoch, TaskType type, long delayTicks) {
		long runTick = currentTick + Math.max(1, delayTicks);
		SCHEDULED_TASKS.computeIfAbsent(runTick, ignored -> new ArrayList<>())
			.add(new ScheduledTask(playerId, epoch, type));
	}

	private static void scheduleIfOnlineLocked(MinecraftServer server, UUID playerId, int epoch, TaskType type, long delayTicks) {
		if (server.getPlayerList().getPlayer(playerId) != null) {
			scheduleTaskLocked(playerId, epoch, type, delayTicks);
		}
	}

	private static void executeDueTasks(PlayerList playerList) {
		while (true) {
			List<ScheduledTask> tasks;
			synchronized (TASK_LOCK) {
				Map.Entry<Long, List<ScheduledTask>> entry = SCHEDULED_TASKS.firstEntry();
				if (entry == null || entry.getKey() > currentTick) {
					return;
				}
				tasks = SCHEDULED_TASKS.remove(entry.getKey());
			}

			if (tasks == null) {
				continue;
			}
			for (ScheduledTask task : tasks) {
				executeTask(playerList, task);
			}
		}
	}

	private static void executeTask(PlayerList playerList, ScheduledTask task) {
		long cooldown;
		int sessionEpoch;
		synchronized (TASK_LOCK) {
			HiddenSession session = getSessionLocked(task.playerId);
			sessionEpoch = session.epoch();
			cooldown = session.cooldownUntilTick();
		}

		if (sessionEpoch != task.epoch) {
			return;
		}

		ServerPlayer target = playerList.getPlayer(task.playerId);
		if (target == null) {
			return;
		}

		if (task.type == TaskType.RESYNC_LIFECYCLE && currentTick < cooldown) {
			synchronized (TASK_LOCK) {
				scheduleTaskLocked(task.playerId, task.epoch, task.type, cooldown - currentTick);
			}
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

			long cooldown;
			synchronized (TASK_LOCK) {
				cooldown = getSessionLocked(hiddenPlayer).cooldownUntilTick();
			}
			if (currentTick < cooldown) {
				continue;
			}

			HideVisibilityManager.applyHideTransition(playerList, target);
		}
	}

	private static HiddenSession getSessionLocked(UUID playerId) {
		return SESSIONS.computeIfAbsent(playerId, ignored -> new HiddenSession());
	}

	private static int nextEpochLocked(UUID playerId) {
		return getSessionLocked(playerId).nextEpoch();
	}

	private static void pruneScheduledTasksForLocked(UUID playerId) {
		for (Map.Entry<Long, List<ScheduledTask>> entry : SCHEDULED_TASKS.entrySet()) {
			List<ScheduledTask> tasks = entry.getValue();
			tasks.removeIf(task -> task.playerId.equals(playerId));
		}
		SCHEDULED_TASKS.entrySet().removeIf(e -> e.getValue().isEmpty());
	}

	private static synchronized void loadHiddenPlayers() {
		HIDDEN_PLAYERS.clear();
		if (persistPath == null || !Files.exists(persistPath)) {
			return;
		}

		try {
			for (String line : Files.readAllLines(persistPath, StandardCharsets.UTF_8)) {
				String value = line.trim();
				if (value.isEmpty()) {
					continue;
				}
				try {
					HIDDEN_PLAYERS.add(UUID.fromString(value));
				} catch (IllegalArgumentException ignored) {
					HIDE.LOGGER.warn("Ignoring malformed hidden-player UUID '{}' in {}", value, persistPath);
				}
			}
		} catch (IOException e) {
			HIDE.LOGGER.warn("Failed to load hidden-player state from {}", persistPath, e);
		}
	}

	private static void saveHiddenPlayersAsync() {
		Path path = persistPath;
		if (path == null) {
			return;
		}
		List<String> snapshot = HIDDEN_PLAYERS.stream().map(UUID::toString).sorted().collect(Collectors.toList());
		SAVE_EXECUTOR.execute(() -> writeSnapshot(path, snapshot));
	}

	private static synchronized void saveHiddenPlayersSync() {
		Path path = persistPath;
		if (path == null) {
			return;
		}
		List<String> snapshot = HIDDEN_PLAYERS.stream().map(UUID::toString).sorted().collect(Collectors.toList());
		writeSnapshot(path, snapshot);
	}

	private static void writeSnapshot(Path path, List<String> snapshot) {
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.write(path, snapshot, StandardCharsets.UTF_8);
		} catch (IOException e) {
			HIDE.LOGGER.warn("Failed to save hidden-player state to {}", path, e);
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

		int nextEpoch() {
			epoch++;
			return epoch;
		}

		int epoch() {
			return epoch;
		}

		long cooldownUntilTick() {
			return cooldownUntilTick;
		}

		void updateCooldownUntil(long tick) {
			cooldownUntilTick = tick;
		}
	}
}

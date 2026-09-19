package com.synsenetwork.vanadium.tracking;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.mixin.server.network.ServerEntityAccessor;
import com.synsenetwork.vanadium.mixin.server.world.TrackedEntityAccessor;
import com.synsenetwork.vanadium.tick.Stage;
import it.unimi.dsi.fastutil.objects.Reference2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap.TrackedEntity;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Inverted entity-tracking index, replacing the O(entities x players) scans in
 * tickEntityMovement. Algorithm derived from VMP-fabric's NearbyEntityTracking (MIT,
 * Copyright (c) ishland), restructured for Vanadium's wave model. The caller maintains
 * the index; independent player diffs prepare actions, then one task per tracker applies
 * them in player order. TRACKING cells ensure each tracker's listener set and entry state
 * are only touched by one worker.
 *
 * <p>Each tracker is painted onto an {@link AreaMap} square of its own track distance, so
 * "which trackers must consider player P" is one lookup. Freshly loaded trackers spend
 * {@link #STAGING_TICKS} in a brute-force staging queue first — short-lived entities (items,
 * arrows, XP) usually die before ever paying the paint/erase churn. Trackers with no player
 * in range are not ticked at all; the first watcher triggers a forced full resync (see
 * {@link #forceResync}).
 *
 * <p>{@link #add}/{@link #remove} are synchronized because entity load/unload can run on
 * workers mid-wave (spawns); {@link #tick} runs on the server thread between waves.
 */
public final class NearbyTrackers {
    private static final int STAGING_TICKS = 200;
    private static final int MIN_PARALLEL_COMPARISONS = 1024;
    private static final byte REMOVE = 1;
    private static final byte SEND = 2;
    private static final byte UPDATE = 4;

    private final AreaMap<TrackedEntity> areaMap = new AreaMap<>();
    /** Painted trackers -> chunk key their square is centered on. */
    private final Reference2LongOpenHashMap<TrackedEntity> paintedChunk = new Reference2LongOpenHashMap<>();
    /** Each player's known trackers and reusable action row. */
    private final Reference2ObjectOpenHashMap<ServerPlayer, PlayerDiff> playerDiffs = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<TrackedEntity, TrackingTask> trackingTasks = new Reference2ObjectOpenHashMap<>();
    /** Last observed position identity per tracker/player; a fresh key counts as moved. */
    private final Reference2ObjectOpenHashMap<TrackedEntity, Vec3> trackerPrevPos = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ServerPlayer, Vec3> playerPrevPos = new Reference2ObjectOpenHashMap<>();

    /** Staged trackers in arrival order, with the tick they were added. */
    private final Reference2LongLinkedOpenHashMap<TrackedEntity> staging = new Reference2LongLinkedOpenHashMap<>();

    /** Per-tick scratch, cleared at the end of every tick. */
    private final ReferenceOpenHashSet<TrackedEntity> movedTrackers = new ReferenceOpenHashSet<>();

    private long tick;

    /**
     * Registers a freshly loaded tracker and runs the initial visibility pass (the same
     * per-player scan vanilla loadEntity does). Called from loadEntity in both config modes.
     */
    public synchronized void add(TrackedEntity tracker, List<ServerPlayer> players) {
        if (entity(tracker) instanceof ServerPlayer player) {
            playerDiffs.put(player, new PlayerDiff(player));
        }
        trackingTasks.put(tracker, new TrackingTask(tracker));
        staging.put(tracker, tick);
        for (ServerPlayer player : players) {
            tracker.updatePlayer(player);
        }
    }

    /** Unregisters a tracker; the caller still runs vanilla's stopTracking broadcast after. */
    public synchronized void remove(TrackedEntity tracker) {
        if (entity(tracker) instanceof ServerPlayer player) {
            removePlayer(player);
        }
        staging.removeLong(tracker);
        trackingTasks.remove(tracker);
        if (paintedChunk.containsKey(tracker)) {
            paintedChunk.removeLong(tracker);
            areaMap.remove(tracker);
        }
        trackerPrevPos.remove(tracker);
        for (PlayerDiff diff : playerDiffs.values()) {
            diff.known.remove(tracker);
        }
    }

    private void removePlayer(ServerPlayer player) {
        for (TrackedEntity tracker : staging.keySet()) {
            tracker.removePlayer(player);
        }
        PlayerDiff diff = playerDiffs.remove(player);
        playerPrevPos.remove(player);
        if (diff != null) {
            for (TrackedEntity tracker : diff.known) {
                tracker.removePlayer(player);
            }
        }
    }

    /**
     * Prepares the TRACKING stage: the caller maintains the index, independent player diffs
     * run inline or in parallel, then the caller enqueues per-tracker callbacks into cells.
     * The caller begins the stage before this method and drains those cells after it returns.
     */
    public synchronized void tick(List<ServerPlayer> players, DistanceManager tickets) {
        tick++;
        detectMoves();
        migrateStaging();
        tickStaging(players, tickets);
        diffPlayers(players, tickets);
        commitPositions(players);
    }

    /** Records which trackers moved since last tick and repaints chunk-crossers. */
    private void detectMoves() {
        for (var entry : paintedChunk.reference2LongEntrySet()) {
            TrackedEntity tracker = entry.getKey();
            Entity entity = entity(tracker);
            if (trackerPrevPos.get(tracker) != entity.position()) {
                movedTrackers.add(tracker);
            }
            long now = entity.chunkPosition().pack();
            if (now != entry.getLongValue()) {
                areaMap.update(tracker, entity.chunkPosition().x(), entity.chunkPosition().z(), radius(tracker));
                entry.setValue(now);
            }
        }
        for (TrackedEntity tracker : staging.keySet()) {
            if (trackerPrevPos.get(tracker) != entity(tracker).position()) {
                movedTrackers.add(tracker);
            }
        }
    }

    /** Moves surviving trackers into the area map in arrival order, after their staging window. */
    private void migrateStaging() {
        while (!staging.isEmpty()) {
            TrackedEntity tracker = staging.firstKey();
            if (tick - staging.getLong(tracker) <= STAGING_TICKS) {
                break;
            }
            staging.removeFirstLong();
            Entity entity = entity(tracker);
            areaMap.add(tracker, entity.chunkPosition().x(), entity.chunkPosition().z(), radius(tracker));
            paintedChunk.put(tracker, entity.chunkPosition().pack());
        }
    }

    /** Staged trackers update their status against every player on every tick. */
    private void tickStaging(List<ServerPlayer> players, DistanceManager tickets) {
        for (TrackedEntity tracker : staging.keySet()) {
            Entity entity = entity(tracker);
            boolean moved = movedTrackers.contains(tracker);
            boolean shouldTick = moved || entity.needsSync || tickets.inEntityTickingRange(entity.chunkPosition().pack());
            enqueue(tracker, () -> {
                if (shouldTick) {
                    entry(tracker).sendChanges();
                }
                for (ServerPlayer player : players) {
                    tracker.updatePlayer(player);
                }
            });
        }
    }

    /** Diffs each player's in-range tracker set against what they knew last tick. */
    private void diffPlayers(List<ServerPlayer> players, DistanceManager tickets) {
        if (players.isEmpty()) return;
        int slot = 0;
        for (TrackingTask task : trackingTasks.values()) task.slot = slot++;
        List<PlayerDiff> diffs = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            PlayerDiff diff = playerDiffs.get(player);
            if (diff == null) continue;
            diff.current = areaMap.objectsAt(player.chunkPosition().pack());
            diff.playerMoved = playerPrevPos.get(player) != player.position();
            diff.tickets = tickets;
            if (diff.actions.length < trackingTasks.size()) {
                diff.actions = new byte[Math.max(trackingTasks.size(), diff.actions.length * 2)];
            }
            diffs.add(diff);
        }
        // tick() excludes membership/index changes. Each job owns exactly one player's known set;
        // workers write separate action arrays and never invoke tracking callbacks. Slots are
        // assigned on the caller and remain stable through both preparation and tracking barriers.
        long comparisons = 0;
        for (PlayerDiff diff : diffs) comparisons += (long) diff.known.size() + diff.current.size();
        if (diffs.size() < 2 || comparisons < MIN_PARALLEL_COMPARISONS) {
            for (PlayerDiff diff : diffs) diff.run();
        } else {
            Vanadium.scheduler.prepare(diffs);
        }
        for (TrackingTask task : trackingTasks.values()) {
            for (PlayerDiff diff : diffs) {
                if (diff.actions[task.slot] != 0) {
                    task.diffs = diffs;
                    enqueue(task.tracker, task);
                    break;
                }
            }
        }
    }

    private final class PlayerDiff implements Runnable {
        private final ServerPlayer player;
        private final ReferenceOpenHashSet<TrackedEntity> known = new ReferenceOpenHashSet<>();
        private Set<TrackedEntity> current;
        private boolean playerMoved;
        private DistanceManager tickets;
        private byte[] actions = new byte[0];

        private PlayerDiff(ServerPlayer player) {
            this.player = player;
        }

        @Override public void run() {
            Arrays.fill(actions, 0, trackingTasks.size(), (byte) 0);
            for (var iterator = known.iterator(); iterator.hasNext(); ) {
                TrackedEntity tracker = iterator.next();
                if (!current.contains(tracker)) {
                    iterator.remove();
                    actions[trackingTasks.get(tracker).slot] = REMOVE;
                }
            }
            for (TrackedEntity tracker : current) {
                boolean fresh = known.add(tracker);
                boolean send = entity(tracker).needsSync || tickets.inEntityTickingRange(entity(tracker).chunkPosition().pack());
                boolean update = fresh || playerMoved || movedTrackers.contains(tracker);
                if (send || update) {
                    actions[trackingTasks.get(tracker).slot] = (byte) ((send ? SEND : 0) | (update ? UPDATE : 0));
                }
            }
        }
    }

    /** Reads the completed player-owned rows; one scheduled task owns all callbacks for this tracker. */
    private static final class TrackingTask implements Runnable {
        private final TrackedEntity tracker;
        private int slot;
        private List<PlayerDiff> diffs;

        private TrackingTask(TrackedEntity tracker) { this.tracker = tracker; }

        @Override public void run() {
            boolean sent = false;
            try {
                for (PlayerDiff diff : diffs) {
                    byte action = diff.actions[slot];
                    if ((action & REMOVE) != 0) {
                        tracker.removePlayer(diff.player);
                    } else {
                        if (!sent && (action & SEND) != 0) {
                            entry(tracker).sendChanges();
                            sent = true;
                        }
                        if ((action & UPDATE) != 0) tracker.updatePlayer(diff.player);
                    }
                }
            } finally { diffs = null; }
        }
    }

    /** Publishes this tick's positions as "previous" and clears the per-tick scratch. */
    private void commitPositions(List<ServerPlayer> players) {
        for (TrackedEntity tracker : movedTrackers) {
            Entity entity = entity(tracker);
            trackerPrevPos.put(tracker, entity.position());
            access(tracker).vanadium$setTrackedSection(SectionPos.of(entity));
        }
        movedTrackers.clear();
        for (ServerPlayer player : players) {
            playerPrevPos.put(player, player.position());
        }
    }

    private void enqueue(TrackedEntity tracker, Runnable task) {
        Entity entity = entity(tracker);
        Vanadium.scheduler.enqueue(Stage.TRACKING, entity.chunkPosition().x(), entity.chunkPosition().z(), task, null);
    }

    private static Entity entity(TrackedEntity tracker) {
        return access(tracker).vanadium$entity();
    }

    private static ServerEntity entry(TrackedEntity tracker) {
        return access(tracker).vanadium$entry();
    }

    private static TrackedEntityAccessor access(TrackedEntity tracker) {
        return (TrackedEntityAccessor) (Object) tracker;
    }

    private static int radius(TrackedEntity tracker) {
        return (int) Math.ceil(access(tracker).vanadium$maxTrackDistance() / 16.0) + 1;
    }

    /**
     * Forces the entry's next tick to send a full absolute position/velocity/data sync.
     * Watcher-less trackers are not ticked, so their entry state goes stale; this runs just
     * before the first listener is added (while the listener set is still empty, so nothing
     * is sent) to re-baseline the entry for the new watcher.
     */
    public static void forceResync(ServerEntity entry) {
        ServerEntityAccessor access = (ServerEntityAccessor) entry;
        access.vanadium$setTrackingTick(Mth.roundToward(access.vanadium$trackingTick(), access.vanadium$tickInterval()));
        access.vanadium$setUpdatesWithoutVehicle(1 << 16);
        access.vanadium$entity().needsSync = true;
        entry.sendChanges();
    }
}

package com.synsenetwork.vanadium.tracking;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.mixin.server.network.EntityTrackerEntryAccessor;
import com.synsenetwork.vanadium.mixin.server.world.EntityTrackerAccessor;
import com.synsenetwork.vanadium.tick.Stage;
import it.unimi.dsi.fastutil.objects.Reference2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
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
 * Copyright (c) ishland), restructured for Vanadium's wave model: every decision (index
 * maintenance, per-player diff, moved detection) runs serially on the server thread, and the
 * per-tracker work it produces — entry ticks, tracked-status updates, stop-tracking — is
 * enqueued into TRACKING cells keyed by the tracked entity's chunk, so a tracker's listener
 * set and entry state are only ever touched by one worker.
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

    private final AreaMap<TrackedEntity> areaMap = new AreaMap<>();
    /** Painted trackers -> chunk key their square is centered on. */
    private final Reference2LongOpenHashMap<TrackedEntity> paintedChunk = new Reference2LongOpenHashMap<>();
    /** Trackers each player currently knows (mirrors the pair state built by the diff). */
    private final Reference2ObjectOpenHashMap<ServerPlayer, ReferenceOpenHashSet<TrackedEntity>> playerTrackers = new Reference2ObjectOpenHashMap<>();
    /** Last observed position identity per tracker/player; a fresh key counts as moved. */
    private final Reference2ObjectOpenHashMap<TrackedEntity, Vec3> trackerPrevPos = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ServerPlayer, Vec3> playerPrevPos = new Reference2ObjectOpenHashMap<>();

    /** Staged trackers in arrival order, with the tick they were added. */
    private final Reference2LongLinkedOpenHashMap<TrackedEntity> staging = new Reference2LongLinkedOpenHashMap<>();

    /** Per-tick scratch, cleared at the end of every tick. */
    private final ReferenceOpenHashSet<TrackedEntity> movedTrackers = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<TrackedEntity> tickedOnce = new ReferenceOpenHashSet<>();

    private long tick;

    /**
     * Registers a freshly loaded tracker and runs the initial visibility pass (the same
     * per-player scan vanilla loadEntity does). Called from loadEntity in both config modes.
     */
    public synchronized void add(TrackedEntity tracker, List<ServerPlayer> players) {
        if (entity(tracker) instanceof ServerPlayer player) {
            playerTrackers.put(player, new ReferenceOpenHashSet<>());
        }
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
        if (paintedChunk.containsKey(tracker)) {
            paintedChunk.removeLong(tracker);
            areaMap.remove(tracker);
        }
        trackerPrevPos.remove(tracker);
        for (ReferenceOpenHashSet<TrackedEntity> known : playerTrackers.values()) {
            known.remove(tracker);
        }
    }

    private void removePlayer(ServerPlayer player) {
        for (TrackedEntity tracker : staging.keySet()) {
            tracker.removePlayer(player);
        }
        ReferenceOpenHashSet<TrackedEntity> known = playerTrackers.remove(player);
        if (known != null) {
            for (TrackedEntity tracker : known) {
                tracker.removePlayer(player);
            }
        }
        playerPrevPos.remove(player);
    }

    /**
     * The serial phase of the TRACKING stage: migrate staging, re-index movers, diff each
     * player against the index, and enqueue the resulting per-tracker work into cells. The
     * caller begins the stage before and runs the wave after.
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
        for (ServerPlayer player : players) {
            ReferenceOpenHashSet<TrackedEntity> known = playerTrackers.get(player);
            if (known == null) {
                continue; // tracker not loaded yet; the load-time pass covers this join tick
            }
            Set<TrackedEntity> current = areaMap.objectsAt(player.chunkPosition().pack());
            boolean playerMoved = playerPrevPos.get(player) != player.position();

            for (var iterator = known.iterator(); iterator.hasNext(); ) {
                TrackedEntity tracker = iterator.next();
                if (!current.contains(tracker)) {
                    iterator.remove();
                    enqueue(tracker, () -> tracker.removePlayer(player));
                }
            }
            for (TrackedEntity tracker : current) {
                boolean fresh = known.add(tracker);
                if (tickedOnce.add(tracker) && (entity(tracker).needsSync || tickets.inEntityTickingRange(entity(tracker).chunkPosition().pack()))) {
                    ServerEntity entry = entry(tracker);
                    enqueue(tracker, entry::sendChanges);
                }
                if (fresh || playerMoved || movedTrackers.contains(tracker)) {
                    enqueue(tracker, () -> tracker.updatePlayer(player));
                }
            }
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
        tickedOnce.clear();
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

    private static EntityTrackerAccessor access(TrackedEntity tracker) {
        return (EntityTrackerAccessor) (Object) tracker;
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
        EntityTrackerEntryAccessor access = (EntityTrackerEntryAccessor) entry;
        access.vanadium$setTrackingTick(Mth.roundToward(access.vanadium$trackingTick(), access.vanadium$tickInterval()));
        access.vanadium$setUpdatesWithoutVehicle(1 << 16);
        access.vanadium$entity().needsSync = true;
        entry.sendChanges();
    }
}

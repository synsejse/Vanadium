package com.synsenetwork.vanadium.tracking;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.mixin.server.network.EntityTrackerEntryAccessor;
import com.synsenetwork.vanadium.mixin.server.world.EntityTrackerAccessor;
import com.synsenetwork.vanadium.tick.Stage;
import it.unimi.dsi.fastutil.objects.Reference2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerChunkLoadingManager.EntityTracker;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;

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

    private final AreaMap<EntityTracker> areaMap = new AreaMap<>();
    /** Painted trackers -> chunk key their square is centered on. */
    private final Reference2LongOpenHashMap<EntityTracker> paintedChunk = new Reference2LongOpenHashMap<>();
    /** Trackers each player currently knows (mirrors the pair state built by the diff). */
    private final Reference2ObjectOpenHashMap<ServerPlayerEntity, ReferenceOpenHashSet<EntityTracker>> playerTrackers = new Reference2ObjectOpenHashMap<>();
    /** Last observed position identity per tracker/player; a fresh key counts as moved. */
    private final Reference2ObjectOpenHashMap<EntityTracker, Vec3d> trackerPrevPos = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ServerPlayerEntity, Vec3d> playerPrevPos = new Reference2ObjectOpenHashMap<>();

    /** Staged trackers in arrival order, with the tick they were added. */
    private final Reference2LongLinkedOpenHashMap<EntityTracker> staging = new Reference2LongLinkedOpenHashMap<>();

    /** Per-tick scratch, cleared at the end of every tick. */
    private final ReferenceOpenHashSet<EntityTracker> movedTrackers = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<EntityTracker> tickedOnce = new ReferenceOpenHashSet<>();

    private long tick;

    /**
     * Registers a freshly loaded tracker and runs the initial visibility pass (the same
     * per-player scan vanilla loadEntity does). Called from loadEntity in both config modes.
     */
    public synchronized void add(EntityTracker tracker, List<ServerPlayerEntity> players) {
        if (entity(tracker) instanceof ServerPlayerEntity player) {
            playerTrackers.put(player, new ReferenceOpenHashSet<>());
        }
        staging.put(tracker, tick);
        for (ServerPlayerEntity player : players) {
            tracker.updateTrackedStatus(player);
        }
    }

    /** Unregisters a tracker; the caller still runs vanilla's stopTracking broadcast after. */
    public synchronized void remove(EntityTracker tracker) {
        if (entity(tracker) instanceof ServerPlayerEntity player) {
            removePlayer(player);
        }
        staging.removeLong(tracker);
        if (paintedChunk.containsKey(tracker)) {
            paintedChunk.removeLong(tracker);
            areaMap.remove(tracker);
        }
        trackerPrevPos.remove(tracker);
        for (ReferenceOpenHashSet<EntityTracker> known : playerTrackers.values()) {
            known.remove(tracker);
        }
    }

    private void removePlayer(ServerPlayerEntity player) {
        for (EntityTracker tracker : staging.keySet()) {
            tracker.stopTracking(player);
        }
        ReferenceOpenHashSet<EntityTracker> known = playerTrackers.remove(player);
        if (known != null) {
            for (EntityTracker tracker : known) {
                tracker.stopTracking(player);
            }
        }
        playerPrevPos.remove(player);
    }

    /**
     * The serial phase of the TRACKING stage: migrate staging, re-index movers, diff each
     * player against the index, and enqueue the resulting per-tracker work into cells. The
     * caller begins the stage before and runs the wave after.
     */
    public synchronized void tick(List<ServerPlayerEntity> players, ChunkTicketManager tickets) {
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
            EntityTracker tracker = entry.getKey();
            Entity entity = entity(tracker);
            if (trackerPrevPos.get(tracker) != entity.getPos()) {
                movedTrackers.add(tracker);
            }
            long now = entity.getChunkPos().toLong();
            if (now != entry.getLongValue()) {
                areaMap.update(tracker, entity.getChunkPos().x, entity.getChunkPos().z, radius(tracker));
                entry.setValue(now);
            }
        }
        for (EntityTracker tracker : staging.keySet()) {
            if (trackerPrevPos.get(tracker) != entity(tracker).getPos()) {
                movedTrackers.add(tracker);
            }
        }
    }

    /** Moves surviving trackers into the area map in arrival order, after their staging window. */
    private void migrateStaging() {
        while (!staging.isEmpty()) {
            EntityTracker tracker = staging.firstKey();
            if (tick - staging.getLong(tracker) <= STAGING_TICKS) {
                break;
            }
            staging.removeFirstLong();
            Entity entity = entity(tracker);
            areaMap.add(tracker, entity.getChunkPos().x, entity.getChunkPos().z, radius(tracker));
            paintedChunk.put(tracker, entity.getChunkPos().toLong());
        }
    }

    /** Staged trackers update their status against every player on every tick. */
    private void tickStaging(List<ServerPlayerEntity> players, ChunkTicketManager tickets) {
        for (EntityTracker tracker : staging.keySet()) {
            Entity entity = entity(tracker);
            boolean moved = movedTrackers.contains(tracker);
            boolean shouldTick = moved || tickets.shouldTickEntities(entity.getChunkPos().toLong());
            enqueue(tracker, () -> {
                if (shouldTick) {
                    entry(tracker).tick();
                }
                for (ServerPlayerEntity player : players) {
                    tracker.updateTrackedStatus(player);
                }
            });
        }
    }

    /** Diffs each player's in-range tracker set against what they knew last tick. */
    private void diffPlayers(List<ServerPlayerEntity> players, ChunkTicketManager tickets) {
        for (ServerPlayerEntity player : players) {
            ReferenceOpenHashSet<EntityTracker> known = playerTrackers.get(player);
            if (known == null) {
                continue; // tracker not loaded yet; the load-time pass covers this join tick
            }
            Set<EntityTracker> current = areaMap.objectsAt(player.getChunkPos().toLong());
            boolean playerMoved = playerPrevPos.get(player) != player.getPos();

            for (var iterator = known.iterator(); iterator.hasNext(); ) {
                EntityTracker tracker = iterator.next();
                if (!current.contains(tracker)) {
                    iterator.remove();
                    enqueue(tracker, () -> tracker.stopTracking(player));
                }
            }
            for (EntityTracker tracker : current) {
                boolean fresh = known.add(tracker);
                if (tickedOnce.add(tracker) && tickets.shouldTickEntities(entity(tracker).getChunkPos().toLong())) {
                    EntityTrackerEntry entry = entry(tracker);
                    enqueue(tracker, entry::tick);
                }
                if (fresh || playerMoved || movedTrackers.contains(tracker)) {
                    enqueue(tracker, () -> tracker.updateTrackedStatus(player));
                }
            }
        }
    }

    /** Publishes this tick's positions as "previous" and clears the per-tick scratch. */
    private void commitPositions(List<ServerPlayerEntity> players) {
        for (EntityTracker tracker : movedTrackers) {
            Entity entity = entity(tracker);
            trackerPrevPos.put(tracker, entity.getPos());
            access(tracker).vanadium$setTrackedSection(ChunkSectionPos.from(entity));
        }
        movedTrackers.clear();
        tickedOnce.clear();
        for (ServerPlayerEntity player : players) {
            playerPrevPos.put(player, player.getPos());
        }
    }

    private void enqueue(EntityTracker tracker, Runnable task) {
        Entity entity = entity(tracker);
        Vanadium.scheduler.enqueue(Stage.TRACKING, entity.getChunkPos().x, entity.getChunkPos().z, task);
    }

    private static Entity entity(EntityTracker tracker) {
        return access(tracker).vanadium$entity();
    }

    private static EntityTrackerEntry entry(EntityTracker tracker) {
        return access(tracker).vanadium$entry();
    }

    private static EntityTrackerAccessor access(EntityTracker tracker) {
        return (EntityTrackerAccessor) (Object) tracker;
    }

    private static int radius(EntityTracker tracker) {
        return (int) Math.ceil(access(tracker).vanadium$maxTrackDistance() / 16.0) + 1;
    }

    /**
     * Forces the entry's next tick to send a full absolute position/velocity/data sync.
     * Watcher-less trackers are not ticked, so their entry state goes stale; this runs just
     * before the first listener is added (while the listener set is still empty, so nothing
     * is sent) to re-baseline the entry for the new watcher.
     */
    public static void forceResync(EntityTrackerEntry entry) {
        EntityTrackerEntryAccessor access = (EntityTrackerEntryAccessor) entry;
        access.vanadium$setTrackingTick(MathHelper.roundUpToMultiple(access.vanadium$trackingTick(), access.vanadium$tickInterval()));
        access.vanadium$setUpdatesWithoutVehicle(1 << 16);
        access.vanadium$entity().velocityDirty = true;
        entry.tick();
    }
}

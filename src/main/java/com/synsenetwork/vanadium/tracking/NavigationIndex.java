package com.synsenetwork.vanadium.tracking;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Conservative candidate index. Dirty navigations remain candidates until a safe tick-boundary refresh.
 *
 * <p>Invalidation is the hot path (every moving mob, every tick, on any worker) and takes no lock:
 * a mob's first invalidation after a refresh flips its own dirty flag and pushes it onto a
 * thread-striped lock-free queue; later invalidations only read that flag. The index monitor
 * guards membership, area painting and candidate snapshots, which are comparatively rare.
 */
public final class NavigationIndex extends AbstractSet<Mob> {
    private static final int MIN_QUERIES_FOR_REFRESH = 2;
    private static final int MAX_INDEXED_PATH_NODES = 256;
    private static final int STRIPES = 64;
    private static final ClassValue<Boolean> VANILLA_PREDICATE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("shouldRecomputePath", BlockPos.class).getDeclaringClass() == PathNavigation.class;
            } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
        }
    };
    private final Set<Mob> members = new ReferenceOpenHashSet<>();
    private final Set<Mob> unbounded = new ReferenceOpenHashSet<>();
    private final AreaMap<Mob> areas = new AreaMap<>();
    /** Dirty mobs, striped by invalidating thread. May hold departed members; readers filter them. */
    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Object>[] dirty = new ConcurrentLinkedQueue[STRIPES];
    private final LongAdder dirtyCount = new LongAdder();
    /** Any invalidation since the last refresh; written only when it changes. */
    private volatile boolean touched;
    private int queriesSinceRefresh;

    public NavigationIndex() {
        for (int i = 0; i < STRIPES; i++) dirty[i] = new ConcurrentLinkedQueue<>();
    }

    public static void invalidate(Entity entity) {
        if (entity instanceof Mob mob && ((NavigationState) mob).vanadium$navigationMember()
                && entity.level() instanceof ServerLevel level) {
            ((NavigationAccess) level).vanadium$navigations().markDirty(mob);
        }
    }

    private void markDirty(Mob mob) {
        if (!touched) touched = true;
        var flag = ((NavigationState) mob).vanadium$navigationDirty();
        if (!flag.get() && flag.compareAndSet(false, true)) {
            dirtyCount.increment();
            dirty[(int) Thread.currentThread().threadId() & (STRIPES - 1)].add(mob);
        }
    }

    @Override public boolean add(Mob mob) {
        synchronized (this) {
            if (!members.add(mob)) return false;
            ((NavigationState) mob).vanadium$setNavigationMember(true);
        }
        markDirty(mob);
        return true;
    }

    @Override public synchronized boolean remove(Object object) {
        if (!members.remove(object)) return false;
        Mob mob = (Mob) object;
        ((NavigationState) mob).vanadium$setNavigationMember(false);
        unbounded.remove(mob);
        areas.remove(mob);
        return true;
    }

    @Override public synchronized int size() { return members.size(); }
    @Override public synchronized boolean contains(Object object) { return members.contains(object); }

    @Override public synchronized Iterator<Mob> iterator() {
        Iterator<Mob> snapshot = new ArrayList<>(members).iterator();
        return new Iterator<>() {
            private Mob current;
            public boolean hasNext() { return snapshot.hasNext(); }
            public Mob next() { return current = snapshot.next(); }
            public void remove() {
                if (current == null) throw new IllegalStateException();
                NavigationIndex.this.remove(current);
                current = null;
            }
        };
    }

    public List<Mob> candidates(BlockPos pos) {
        long dirtyMobs = dirtyCount.sum();
        synchronized (this) {
            if (queriesSinceRefresh < MIN_QUERIES_FOR_REFRESH) queriesSinceRefresh++;
            Set<Mob> nearby = areas.objectsAt(ChunkPos.pack(pos));
            if (dirtyMobs <= 0 && unbounded.isEmpty()) return new ArrayList<>(nearby);
            // A full snapshot is cheaper than merging candidate sets that already cover the population.
            if (nearby.size() + dirtyMobs + unbounded.size() >= members.size()) return new ArrayList<>(members);
            Set<Mob> candidates = new ReferenceOpenHashSet<>(nearby);
            candidates.addAll(unbounded);
            for (ConcurrentLinkedQueue<Object> stripe : dirty) {
                for (Object entry : stripe) {
                    if (entry instanceof Mob mob && ((NavigationState) mob).vanadium$navigationMember()) candidates.add(mob);
                }
            }
            return new ArrayList<>(candidates);
        }
    }

    /**
     * Called between world ticks. Never acquire a navigation monitor while holding this index's lock.
     * Only entries queued before the refresh starts are processed; later invalidations stay dirty.
     */
    public void refresh() {
        int recentQueries;
        synchronized (this) {
            recentQueries = queriesSinceRefresh;
            queriesSinceRefresh = 0;
        }
        boolean changedAgain = touched;
        if (changedAgain) touched = false;
        long dirtyMobs = dirtyCount.sum();
        if (dirtyMobs <= 0) return;
        // Rebuilding most paths costs more than one full scan. Keep their conservative
        // fallback until edits become frequent or the pending paths stop changing.
        if (changedAgain && recentQueries < MIN_QUERIES_FOR_REFRESH && dirtyMobs > size() / 2) return;

        Object marker = new Object();
        boolean[] marked = new boolean[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            // An entry added to a skipped empty stripe after this check waits for the next refresh.
            if (!dirty[i].isEmpty()) {
                dirty[i].add(marker);
                marked[i] = true;
            }
        }
        for (int i = 0; i < STRIPES; i++) {
            if (!marked[i]) continue;
            ConcurrentLinkedQueue<Object> stripe = dirty[i];
            Object entry;
            while ((entry = stripe.poll()) != marker && entry != null) {
                if (!(entry instanceof Mob mob)) continue; // A stale marker from an interrupted refresh.
                NavigationState state = (NavigationState) mob;
                // Clear first: an invalidation racing with the snapshot below re-queues the mob.
                state.vanadium$navigationDirty().set(false);
                dirtyCount.decrement();
                if (!state.vanadium$navigationMember()) continue;
                PathNavigation navigation = mob.getNavigation();
                Bounds bounds;
                synchronized (navigation) {
                    bounds = VANILLA_PREDICATE.get(navigation.getClass()) ? bounds(mob, navigation.getPath()) : Bounds.UNBOUNDED;
                }
                synchronized (this) {
                    if (!members.contains(mob)) continue;
                    unbounded.remove(mob);
                    if (bounds == null || bounds == Bounds.UNBOUNDED) {
                        areas.remove(mob);
                        if (bounds != null) unbounded.add(mob);
                    } else if (areas.contains(mob)) {
                        areas.update(mob, bounds.x, bounds.z, bounds.radius);
                    } else {
                        areas.add(mob, bounds.x, bounds.z, bounds.radius);
                    }
                }
            }
        }
    }

    private static Bounds bounds(Mob mob, Path path) {
        if (path == null || path.isDone() || path.getNodeCount() == 0) return null;
        var end = path.getEndNode();
        double x = (end.x + mob.getX()) / 2.0;
        double z = (end.z + mob.getZ()) / 2.0;
        int remaining = path.getNodeCount() - path.getNextNodeIndex();
        // Limit index memory for very long modded paths; the exact vanilla predicate still runs.
        if (remaining > MAX_INDEXED_PATH_NODES || !Double.isFinite(x) || !Double.isFinite(z)) return Bounds.UNBOUNDED;
        return new Bounds((int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0), (remaining + 31) / 16);
    }

    private record Bounds(int x, int z, int radius) {
        static final Bounds UNBOUNDED = new Bounds(0, 0, -1);
    }
}

package com.synsenetwork.vanadium.tracking;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.pathfinder.Path;

/** Conservative candidate index. Dirty navigations remain candidates until a safe tick-boundary refresh. */
public final class NavigationIndex extends AbstractSet<Mob> {
    private static final int MIN_QUERIES_FOR_REFRESH = 2;
    private static final int MAX_INDEXED_PATH_NODES = 256;
    private static final ClassValue<Boolean> VANILLA_PREDICATE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("shouldRecomputePath", BlockPos.class).getDeclaringClass() == PathNavigation.class;
            } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
        }
    };
    private final Set<Mob> members = new ReferenceOpenHashSet<>();
    private final Set<Mob> dirty = new ReferenceOpenHashSet<>();
    private final Set<Mob> unbounded = new ReferenceOpenHashSet<>();
    private final AreaMap<Mob> areas = new AreaMap<>();
    private long generation;
    private long generationAtPreviousRefresh = -1;
    private int queriesSinceRefresh;

    public static void invalidate(Entity entity) {
        if (entity instanceof Mob mob && entity.level() instanceof ServerLevel level) {
            ((NavigationAccess) level).vanadium$navigations().markDirty(mob);
        }
    }

    private synchronized void markDirty(Mob mob) {
        if (members.contains(mob)) {
            dirty.add(mob);
            generation++;
        }
    }

    @Override public synchronized boolean add(Mob mob) {
        if (!members.add(mob)) return false;
        dirty.add(mob);
        generation++;
        return true;
    }

    @Override public synchronized boolean remove(Object object) {
        if (!members.remove(object)) return false;
        Mob mob = (Mob) object;
        dirty.remove(mob);
        unbounded.remove(mob);
        areas.remove(mob);
        generation++;
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

    public synchronized List<Mob> candidates(BlockPos pos) {
        if (queriesSinceRefresh < MIN_QUERIES_FOR_REFRESH) queriesSinceRefresh++;
        Set<Mob> nearby = areas.objectsAt(ChunkPos.pack(pos));
        if (dirty.isEmpty() && unbounded.isEmpty()) return new ArrayList<>(nearby);
        // A full snapshot is cheaper than merging candidate sets that already cover the population.
        if ((long) nearby.size() + dirty.size() + unbounded.size() >= members.size()) return new ArrayList<>(members);
        Set<Mob> candidates = new ReferenceOpenHashSet<>(nearby);
        candidates.addAll(dirty);
        candidates.addAll(unbounded);
        return new ArrayList<>(candidates);
    }

    /** Called between world ticks. Never acquire a navigation monitor while holding this index's lock. */
    public void refresh() {
        List<Mob> changed;
        long version;
        synchronized (this) {
            boolean changedAgain = generationAtPreviousRefresh != generation;
            generationAtPreviousRefresh = generation;
            int recentQueries = queriesSinceRefresh;
            queriesSinceRefresh = 0;
            if (dirty.isEmpty()) return;
            // Rebuilding most paths costs more than one full scan. Keep their conservative
            // fallback until edits become frequent or the pending paths stop changing.
            if (changedAgain && recentQueries < MIN_QUERIES_FOR_REFRESH && dirty.size() > members.size() / 2) return;
            changed = new ArrayList<>(dirty);
            version = generation;
        }
        for (Mob mob : changed) {
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
        synchronized (this) {
            // A concurrent change keeps the fallback active even if its snapshot was already published.
            if (version == generation) dirty.clear();
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

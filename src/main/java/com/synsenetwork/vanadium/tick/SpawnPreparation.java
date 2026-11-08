package com.synsenetwork.vanadium.tick;

import com.synsenetwork.vanadium.Vanadium;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;

/** Count stable entity batches independently, then combine state before any spawn callback can run. */
public final class SpawnPreparation {
    private static final int MIN_PARALLEL_ENTITIES = 1024;
    private static final int TARGET_ENTITIES_PER_JOB = 256;
    private static final FilteredSource C2ME_FILTER = FilteredSource.find("com.ishland.c2me.base.common.util.FilteringIterable");

    private SpawnPreparation() {}

    public static NaturalSpawner.SpawnState count(int chunkCount, Iterable<Entity> entities,
                                                  NaturalSpawner.ChunkGetter chunks,
                                                  LocalMobCapCalculator localCaps, ChunkMap chunkMap) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            return NaturalSpawner.createState(chunkCount, entities, chunks, localCaps);
        }
        // C2ME restricts counting to simulated chunks through an unsized filtering wrapper; unwrap it so
        // the per-entity chunk lookup runs inside the parallel batches instead of a serial copy.
        Predicate<Entity> filter = null;
        if (C2ME_FILTER != null && C2ME_FILTER.matches(entities)) {
            Iterable<Entity> source = C2ME_FILTER.delegate(entities);
            if (source instanceof Collection<Entity>) {
                filter = C2ME_FILTER.predicate(entities);
                entities = source;
            }
        }
        List<Entity> snapshot;
        if (entities instanceof Collection<Entity> collection) {
            if (collection.size() < MIN_PARALLEL_ENTITIES) {
                return NaturalSpawner.createState(chunkCount, filtered(entities, filter), chunks, localCaps);
            }
            snapshot = new ArrayList<>(collection);
        } else {
            // Preserve support for unsized, possibly single-pass iterables from other implementations.
            snapshot = new ArrayList<>();
            entities.forEach(snapshot::add);
        }
        if (snapshot.size() < MIN_PARALLEL_ENTITIES) {
            return NaturalSpawner.createState(chunkCount, filtered(snapshot, filter), chunks, localCaps);
        }
        Predicate<Entity> batchFilter = filter;

        int jobs = Math.min(Vanadium.scheduler.workerCount() + 1,
                (snapshot.size() + TARGET_ENTITIES_PER_JOB - 1) / TARGET_ENTITIES_PER_JOB);
        int batchSize = (snapshot.size() + jobs - 1) / jobs;
        List<Runnable> tasks = new ArrayList<>(jobs);
        NaturalSpawner.SpawnState[] partial = new NaturalSpawner.SpawnState[jobs];
        for (int i = 0; i < jobs; i++) {
            int index = i;
            List<Entity> batch = snapshot.subList(i * batchSize, Math.min(snapshot.size(), (i + 1) * batchSize));
            tasks.add(() -> partial[index] = NaturalSpawner.createState(chunkCount, filtered(batch, batchFilter), chunks,
                    new LocalMobCapCalculator(chunkMap)));
        }
        Vanadium.scheduler.prepare(tasks);
        NaturalSpawner.SpawnState result = partial[0];
        // Merge contiguous batches in source order, including charge order for floating-point sums.
        for (int i = 1; i < partial.length; i++) {
            merge(result, partial[i]);
        }
        return result;
    }

    private static void merge(NaturalSpawner.SpawnState result, NaturalSpawner.SpawnState state) {
        for (var entry : state.mobCategoryCounts.object2IntEntrySet()) {
            result.mobCategoryCounts.addTo(entry.getKey(), entry.getIntValue());
        }
        result.spawnPotential.charges.addAll(state.spawnPotential.charges);
        LocalMobCapCalculator target = result.localMobCapCalculator;
        LocalMobCapCalculator source = state.localMobCapCalculator;
        target.playersNearChunk.putAll(source.playersNearChunk);
        source.playerMobCounts.forEach((player, counts) -> {
            var existing = target.playerMobCounts.get(player);
            if (existing == null) target.playerMobCounts.put(player, counts);
            else for (var entry : counts.counts.object2IntEntrySet()) {
                existing.counts.mergeInt(entry.getKey(), entry.getIntValue(), Integer::sum);
            }
        });
    }

    private static Iterable<Entity> filtered(Iterable<Entity> source, Predicate<Entity> filter) {
        if (filter == null) return source;
        return () -> new Iterator<>() {
            private final Iterator<Entity> delegate = source.iterator();
            private Entity next;

            @Override public boolean hasNext() {
                while (next == null && delegate.hasNext()) {
                    Entity candidate = delegate.next();
                    if (filter.test(candidate)) next = candidate;
                }
                return next != null;
            }

            @Override public Entity next() {
                if (!hasNext()) throw new NoSuchElementException();
                Entity result = next;
                next = null;
                return result;
            }
        };
    }

    /** Reflective view of a mod's {@code (delegate, predicate)} filtering iterable. */
    private record FilteredSource(Class<?> type, Field delegate, Field predicate) {
        static FilteredSource find(String className) {
            try {
                Class<?> type = Class.forName(className, false, SpawnPreparation.class.getClassLoader());
                Field delegate = type.getDeclaredField("delegate");
                Field predicate = type.getDeclaredField("predicate");
                delegate.setAccessible(true);
                predicate.setAccessible(true);
                return new FilteredSource(type, delegate, predicate);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null; // Not installed or changed shape: keep the generic snapshot path.
            }
        }

        boolean matches(Iterable<Entity> entities) {
            return entities.getClass() == type;
        }

        @SuppressWarnings("unchecked")
        Iterable<Entity> delegate(Iterable<Entity> entities) {
            try {
                return (Iterable<Entity>) delegate.get(entities);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("unchecked")
        Predicate<Entity> predicate(Iterable<Entity> entities) {
            try {
                return (Predicate<Entity>) predicate.get(entities);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

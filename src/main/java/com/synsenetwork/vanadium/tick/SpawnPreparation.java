package com.synsenetwork.vanadium.tick;

import com.synsenetwork.vanadium.Vanadium;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;

/** Count stable entity batches independently, then combine state before any spawn callback can run. */
public final class SpawnPreparation {
    private static final int MIN_PARALLEL_ENTITIES = 1024;
    private static final int TARGET_ENTITIES_PER_JOB = 256;

    private SpawnPreparation() {}

    public static NaturalSpawner.SpawnState count(int chunkCount, Iterable<Entity> entities,
                                                  NaturalSpawner.ChunkGetter chunks,
                                                  LocalMobCapCalculator localCaps, ChunkMap chunkMap) {
        if (!Vanadium.config.enabled || !Vanadium.config.parallelSpawning) {
            return NaturalSpawner.createState(chunkCount, entities, chunks, localCaps);
        }
        List<Entity> snapshot;
        if (entities instanceof Collection<Entity> collection) {
            if (collection.size() < MIN_PARALLEL_ENTITIES) return NaturalSpawner.createState(chunkCount, entities, chunks, localCaps);
            snapshot = new ArrayList<>(collection);
        } else {
            // Preserve support for unsized, possibly single-pass iterables from other implementations.
            snapshot = new ArrayList<>();
            entities.forEach(snapshot::add);
        }
        if (snapshot.size() < MIN_PARALLEL_ENTITIES) return NaturalSpawner.createState(chunkCount, snapshot, chunks, localCaps);

        int jobs = Math.min(Vanadium.scheduler.workerCount() + 1,
                (snapshot.size() + TARGET_ENTITIES_PER_JOB - 1) / TARGET_ENTITIES_PER_JOB);
        int batchSize = (snapshot.size() + jobs - 1) / jobs;
        List<Runnable> tasks = new ArrayList<>(jobs);
        NaturalSpawner.SpawnState[] partial = new NaturalSpawner.SpawnState[jobs];
        for (int i = 0; i < jobs; i++) {
            int index = i;
            List<Entity> batch = snapshot.subList(i * batchSize, Math.min(snapshot.size(), (i + 1) * batchSize));
            tasks.add(() -> partial[index] = NaturalSpawner.createState(chunkCount, batch, chunks,
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
}

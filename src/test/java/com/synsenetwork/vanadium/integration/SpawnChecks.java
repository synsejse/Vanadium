package com.synsenetwork.vanadium.integration;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.SpawnPreparation;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.entity.EntityLookup;

final class SpawnChecks {
    static void run(ServerLevel level) throws Exception {
        var map = level.getChunkSource().chunkMap;
        var chunk = level.getChunkAt(new BlockPos(0, 100, 0));
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 2048; i++) {
            var mob = i % 2 == 0 ? new Pig(EntityTypes.PIG, level) : new Zombie(EntityTypes.ZOMBIE, level);
            mob.setPos(i % 16, 100, i / 16 % 16);
            if (i % 3 == 0) mob.setPersistenceRequired();
            entities.add(mob);
        }
        NaturalSpawner.ChunkGetter chunks = (pos, consumer) -> consumer.accept(chunk);
        check(level.getAllEntities() instanceof Collection<?>, "world entity view does not expose its size");
        for (int size : new int[]{0, 256, 1023, 1024, 1025, 2048}) {
            List<Entity> input = entities.subList(0, size);
            var expected = NaturalSpawner.createState(289, input, chunks, new LocalMobCapCalculator(map));
            var counted = SpawnPreparation.count(289, input, chunks, new LocalMobCapCalculator(map), map);
            check(expected.getMobCategoryCounts().equals(counted.getMobCategoryCounts()), "sized count differs at " + size);
            boolean[] iterated = {false};
            Iterable<Entity> singlePass = () -> {
                check(!iterated[0], "unsized input iterated twice");
                iterated[0] = true;
                return input.iterator();
            };
            var unsized = SpawnPreparation.count(289, singlePass, chunks, new LocalMobCapCalculator(map), map);
            check(expected.getMobCategoryCounts().equals(unsized.getMobCategoryCounts()), "unsized count differs at " + size);
        }
        EntityLookup<Entity> lookup = new EntityLookup<>();
        Collection<?> view = (Collection<?>) lookup.getAllEntities();
        check(view.isEmpty(), "new entity view is not empty");
        lookup.add(entities.getFirst());
        check(view.size() == 1 && view.contains(entities.getFirst()), "entity view lost live membership");
        try {
            view.clear();
            throw new AssertionError("entity view permits clearing");
        } catch (UnsupportedOperationException expected) { /* Preserve the read-only contract. */ }
        var iterator = view.iterator();
        iterator.next();
        try {
            iterator.remove();
            throw new AssertionError("entity view permits iterator removal");
        } catch (UnsupportedOperationException expected) { /* Preserve the read-only contract. */ }
        lookup.remove(entities.getFirst());
        check(view.isEmpty(), "entity view retained a removed entity");
        var reference = NaturalSpawner.createState(289, entities, chunks, new LocalMobCapCalculator(map));
        var parallel = SpawnPreparation.count(289, entities, chunks, new LocalMobCapCalculator(map), map);
        check(reference.getMobCategoryCounts().equals(parallel.getMobCategoryCounts()), "parallel spawn counts differ");
        check(reference.getSpawnableChunkCount() == parallel.getSpawnableChunkCount(), "spawnable chunk count changed");
        check(reference.spawnPotential.getPotentialEnergyChange(new BlockPos(0, 100, 0), 1)
                == parallel.spawnPotential.getPotentialEnergyChange(new BlockPos(0, 100, 0), 1), "spawn density changed");
        boolean previous = Vanadium.config.parallelSpawning;
        try {
            Vanadium.config.parallelSpawning = false;
            var serial = SpawnPreparation.count(289, entities, chunks, new LocalMobCapCalculator(map), map);
            check(reference.getMobCategoryCounts().equals(serial.getMobCategoryCounts()), "spawn fallback changed counts");
        } finally { Vanadium.config.parallelSpawning = previous; }
        var merge = SpawnPreparation.class.getDeclaredMethod("merge", NaturalSpawner.SpawnState.class, NaturalSpawner.SpawnState.class);
        merge.setAccessible(true);
        var player = new ServerPlayer(level.getServer(), level.getServer().overworld(),
                new GameProfile(UUID.randomUUID(), "SpawnCaps"), ClientInformation.createDefault());
        var combined = NaturalSpawner.createState(289, List.of(), chunks, new LocalMobCapCalculator(map));
        PotentialCalculator density = new PotentialCalculator();
        for (int i = 0; i < 4; i++) {
            var caps = new LocalMobCapCalculator(map);
            caps.playersNearChunk.put(ChunkPos.pack(0, 0), List.of(player));
            for (int j = 0; j < 20; j++) caps.addMob(new ChunkPos(0, 0), MobCategory.MONSTER);
            var part = NaturalSpawner.createState(289, List.of(), chunks, caps);
            part.mobCategoryCounts.put(MobCategory.MONSTER, 20);
            BlockPos pos = new BlockPos(i + 1, 100, 0);
            part.spawnPotential.addCharge(pos, i + 0.25);
            density.addCharge(pos, i + 0.25);
            merge.invoke(null, combined, part);
        }
        check(combined.getMobCategoryCounts().getInt(MobCategory.MONSTER) == 80, "reduction lost category counts");
        check(!combined.localMobCapCalculator.canSpawn(MobCategory.MONSTER, new ChunkPos(0, 0)), "reduction lost local player caps");
        check(combined.spawnPotential.getPotentialEnergyChange(BlockPos.ZERO, 1)
                == density.getPotentialEnergyChange(BlockPos.ZERO, 1), "reduction changed charge values/order");
        System.out.println("SPAWN_PREPARATION_CHECKS_PASSED entities=2048 counts=" + parallel.getMobCategoryCounts());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.synsenetwork.vanadium.integration;

import com.synsenetwork.vanadium.tracking.NavigationAccess;
import com.synsenetwork.vanadium.tracking.NavigationIndex;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

final class NavigationChecks {
    static void run(ServerLevel level) throws Exception {
        NavigationIndex index = ((NavigationAccess) level).vanadium$navigations();
        Field pathField = PathNavigation.class.getDeclaredField("path");
        pathField.setAccessible(true);
        List<Mob> mobs = new ArrayList<>();
        try {
            for (int i = 0; i < 128; i++) {
                Pig mob = new Pig(EntityTypes.PIG, level);
                int x = (i - 64) * 256;
                mob.setPos(x, 100, -32);
                List<Node> nodes = new ArrayList<>();
                for (int j = 0; j < 64; j++) nodes.add(new Node(x + j, 100, -32));
                pathField.set(mob.getNavigation(), new Path(nodes, new BlockPos(x + 63, 100, -32), true));
                mobs.add(mob);
                index.add(mob);
            }
            index.refresh();
            for (Mob mob : mobs) {
                for (int offset : new int[]{-80, -64, -32, -1, 0, 15, 31, 63, 80, 96}) {
                    BlockPos pos = mob.blockPosition().offset(offset, 0, 0);
                    List<Mob> candidates = index.candidates(pos);
                    for (Mob reference : mobs) {
                        check(!reference.getNavigation().shouldRecomputePath(pos) || candidates.contains(reference), "index missed vanilla navigation");
                    }
                }
            }
            checkRefreshInvalidation(index, mobs);
            Mob moved = mobs.getFirst();
            moved.setPos(100000, 100, 0);
            check(index.candidates(new BlockPos(100000, 100, 0)).contains(moved), "moving mob missed dirty fallback");
            index.refresh();
            moved.getNavigation().moveTo((Path) null, 1);
            check(index.candidates(new BlockPos(100000, 100, 0)).contains(moved), "path replacement missed dirty fallback");
            index.refresh();
            check(!index.candidates(new BlockPos(100000, 100, 0)).contains(moved), "finished path stayed indexed");

            BlockPos edit = mobs.get(64).blockPosition();
            int candidates = index.candidates(edit).size();
            check(candidates < mobs.size(), "dispersed navigation query did not reduce candidates");
            long fullStart = System.nanoTime();
            int fullMatches = 0;
            for (int i = 0; i < 2000; i++) for (Mob mob : mobs) if (mob.getNavigation().shouldRecomputePath(edit)) fullMatches++;
            long fullNanos = System.nanoTime() - fullStart;
            long indexedStart = System.nanoTime();
            int indexedMatches = 0;
            for (int i = 0; i < 2000; i++) for (Mob mob : index.candidates(edit)) if (mob.getNavigation().shouldRecomputePath(edit)) indexedMatches++;
            long indexedNanos = System.nanoTime() - indexedStart;
            check(fullMatches == indexedMatches, "indexed scan changed matches");
            System.out.println("NAVIGATION_CHECKS_PASSED mobs=128 candidates=" + candidates
                    + " fullNanos=" + fullNanos + " indexedNanos=" + indexedNanos);
        } finally {
            for (Mob mob : mobs) index.remove(mob);
        }
    }

    private static void checkRefreshInvalidation(NavigationIndex index, List<Mob> mobs) throws Exception {
        Mob blocked = mobs.getFirst();
        Mob changed = mobs.getLast();
        NavigationIndex.invalidate(blocked);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread refresh;
        synchronized (blocked.getNavigation()) {
            refresh = Thread.ofPlatform().daemon().start(() -> {
                started.countDown();
                try { index.refresh(); }
                catch (Throwable e) { failure.set(e); }
            });
            check(started.await(5, TimeUnit.SECONDS), "refresh thread did not start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (refresh.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
            check(refresh.getState() == Thread.State.BLOCKED, "refresh did not reach navigation snapshot");
            NavigationIndex.invalidate(changed);
        }
        refresh.join(5000);
        check(!refresh.isAlive() && failure.get() == null, "concurrent refresh failed: " + failure.get());
        BlockPos distant = new BlockPos(1000000, 100, 1000000);
        check(index.candidates(distant).contains(changed), "refresh cleared a concurrent invalidation");
        index.refresh();
        check(index.candidates(distant).isEmpty(), "unchanged refresh did not clear dirty entries");

        index.refresh(); // Begin a quiet interval before invalidating the whole population.
        for (Mob mob : mobs) NavigationIndex.invalidate(mob);
        List<Mob> snapshot = index.candidates(distant);
        check(snapshot.size() == mobs.size() && snapshot.containsAll(mobs), "full fallback lost or duplicated mobs");
        snapshot.clear();
        check(index.size() == mobs.size(), "candidate snapshot mutated membership");
        index.refresh();
        check(index.candidates(distant).containsAll(mobs), "deferred refresh lost dirty fallback");
        index.refresh();
        check(index.candidates(distant).isEmpty(), "stable pending entries were not refreshed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

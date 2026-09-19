package com.synsenetwork.vanadium.integration;

import com.synsenetwork.vanadium.tracking.NavigationAccess;
import com.synsenetwork.vanadium.tracking.NavigationIndex;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

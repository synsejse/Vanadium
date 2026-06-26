package com.synsenetwork.vanadium.mixin.utils;

import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;

/**
 * Cancels the C2ME threading-detection mixins that reject Vanadium's worker threads. Vanadium ticks
 * chunks/entities/block entities off the server thread, which trips C2ME's "accessed from a different
 * thread" catchers; the checkerboard scheduling is what keeps that access safe. Registered via the
 * "mixinsquared" entrypoint in fabric.mod.json, so it is only consulted when C2ME (MixinSquared) is present.
 */
public class VanadiumMixinCanceller implements MixinCanceller {

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        return switch (mixinClassName) {
            // Chunk-storage async catcher, and the world-random swap (CheckedThreadLocalRandom) that crashes tickChunk.
            case "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinThreadedAnvilChunkStorage",
                 "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading_detections.random_instances.MixinWorld" ->
                    true;
            default -> false;
        };
    }
}

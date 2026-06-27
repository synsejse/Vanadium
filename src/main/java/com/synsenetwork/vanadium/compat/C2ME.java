package com.synsenetwork.vanadium.compat;

import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;
import java.util.Set;

/** Cancels C2ME's thread-detection mixins that reject Vanadium's safe off-thread ticking. Registered
 *  via the "mixinsquared" entrypoint. C2ME is a hard dependency, so MixinSquared (which C2ME bundles)
 *  is always present and this canceller is always active. */
public final class C2ME implements MixinCanceller {
    private static final Set<String> CANCELLED = Set.of(
            "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinThreadedAnvilChunkStorage",
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading_detections.random_instances.MixinWorld");

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        return CANCELLED.contains(mixinClassName);
    }
}

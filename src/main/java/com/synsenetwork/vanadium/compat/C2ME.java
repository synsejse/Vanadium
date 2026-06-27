package com.synsenetwork.vanadium.compat;

import com.bawnorton.mixinsquared.api.MixinCanceller;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;
import java.util.Set;

/** C2ME compat: the one C2ME detection ({@link #LOADED}), and (via the "mixinsquared" entrypoint)
 *  cancellation of C2ME's thread-detection mixins that reject Vanadium's safe off-thread ticking. */
public final class C2ME implements MixinCanceller {
    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("c2me");

    private static final Set<String> CANCELLED = Set.of(
            "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinThreadedAnvilChunkStorage",
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading_detections.random_instances.MixinWorld");

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        return CANCELLED.contains(mixinClassName);
    }
}

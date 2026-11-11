package com.synsenetwork.vanadium.compat;

import com.bawnorton.mixinsquared.api.MixinCanceller;
import java.util.List;
import java.util.Set;

/**
 * Cancels Lithium mixins that Vanadium replaces with thread-safe equivalents while keeping their Lithium
 * option enabled, so the options that depend on them stay on. Registered via the "mixinsquared" entrypoint.
 *
 * <p>{@code chunk.entity_class_groups.ClassInstanceMultiMapMixin} keeps per-section class-group sets that other
 * regions iterate while the section changes; Vanadium's {@code ClassInstanceMultiMapMixin} implements the same
 * {@code ClassGroupFilterableList} with copy-on-write lists.
 */
public final class Lithium implements MixinCanceller {
    private static final Set<String> CANCELLED = Set.of(
            "net.caffeinemc.mods.lithium.mixin.chunk.entity_class_groups.ClassInstanceMultiMapMixin");

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        return CANCELLED.contains(mixinClassName);
    }
}

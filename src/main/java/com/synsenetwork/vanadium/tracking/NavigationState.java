package com.synsenetwork.vanadium.tracking;

import java.util.concurrent.atomic.AtomicBoolean;

/** Per-mob navigation index state, mixed into {@code Mob}. */
public interface NavigationState {
    /** True while the mob belongs to its level's {@link NavigationIndex}. */
    boolean vanadium$navigationMember();

    void vanadium$setNavigationMember(boolean member);

    /** Set when the mob has been queued as dirty since the last refresh. */
    AtomicBoolean vanadium$navigationDirty();
}

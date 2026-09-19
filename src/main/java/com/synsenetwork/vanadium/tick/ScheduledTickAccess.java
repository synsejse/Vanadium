package com.synsenetwork.vanadium.tick;

import java.util.List;
import net.minecraft.world.ticks.ScheduledTick;

/** Monitor-protected bookkeeping; the caller runs callbacks and waits outside this interface. */
public interface ScheduledTickAccess<T> {
    List<ScheduledTick<T>> vanadium$collect(long time, int limit);
    boolean vanadium$claim(ScheduledTick<T> tick);
    void vanadium$finish();
}

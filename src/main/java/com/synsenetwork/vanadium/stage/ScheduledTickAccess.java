package com.synsenetwork.vanadium.stage;

import java.util.List;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * Bridge between {@link ScheduledTickStage} and {@code LevelTicks}. Collection and cleanup run under the
 * {@code LevelTicks} monitor on the server thread; {@link #vanadium$claim(int)} is lock-free so parallel
 * workers never serialize on a level-wide monitor for every due tick.
 */
public interface ScheduledTickAccess<T> {
    List<ScheduledTick<T>> vanadium$collect(long time, int limit);
    boolean vanadium$claim(int index);
    void vanadium$syncWaveLists();
    void vanadium$finish();
}

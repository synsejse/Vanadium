package com.synsenetwork.vanadium.stage;

/** Mixed into {@code EntityTickList} and {@code ServerLevel}: the level's persistent entity tick tasks. */
public interface EntityTickAccess {
    EntityTickTasks vanadium$entityTicks();
}

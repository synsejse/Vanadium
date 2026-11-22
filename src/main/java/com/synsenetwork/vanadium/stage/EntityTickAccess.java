package com.synsenetwork.vanadium.stage;

/** Mixed into {@code EntityTickList} and {@code ServerLevel}: the level's entity stage owner. */
public interface EntityTickAccess {
    EntityStage vanadium$entityStage();
}

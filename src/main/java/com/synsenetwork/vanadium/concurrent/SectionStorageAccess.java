package com.synsenetwork.vanadium.concurrent;

/** Section storage's installed sections; implemented by {@code SectionStorageMixin}. */
public interface SectionStorageAccess {
    /**
     * The section stored at {@code sectionPos}, or null when it is absent, empty or its column is not loaded. Never
     * loads or waits: one read of the concurrent storage map, as vanilla's protected {@code get}.
     */
    Object vanadium$installed(long sectionPos);
}

package com.synsenetwork.vanadium.concurrent;

import java.util.concurrent.locks.ReentrantLock;

/** Shared by vanilla merge and pickup paths; pair operations lock items in entity-ID order. */
public interface ItemLockAccess {
    ReentrantLock vanadium$itemLock();
}

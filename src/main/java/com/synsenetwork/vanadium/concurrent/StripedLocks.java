package com.synsenetwork.vanadium.concurrent;

import it.unimi.dsi.fastutil.HashCommon;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed array of locks indexed by a hashed long key, so operations on unrelated keys proceed in
 * parallel while operations sharing a key (or hashing to the same stripe) serialize. The two-key
 * variant acquires both stripes in index order, making opposite-order callers deadlock-free.
 */
public final class StripedLocks {
    private final ReentrantLock[] locks;

    /** @param stripes number of locks; must be a power of two. */
    public StripedLocks(int stripes) {
        if (stripes <= 0 || Integer.bitCount(stripes) != 1) {
            throw new IllegalArgumentException("stripes must be a positive power of two (got " + stripes + ")");
        }
        this.locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    int stripeIndex(long key) {
        return (int) HashCommon.mix(key) & (locks.length - 1);
    }

    public void runLocked(long key, Runnable action) {
        ReentrantLock lock = locks[stripeIndex(key)];
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /** Runs the action holding both keys' stripes, acquired in index order (deadlock-free). */
    public void runLocked(long keyA, long keyB, Runnable action) {
        int a = stripeIndex(keyA);
        int b = stripeIndex(keyB);
        if (a == b) {
            runLocked(keyA, action);
            return;
        }
        ReentrantLock first = locks[Math.min(a, b)];
        ReentrantLock second = locks[Math.max(a, b)];
        first.lock();
        try {
            second.lock();
            try {
                action.run();
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }
}

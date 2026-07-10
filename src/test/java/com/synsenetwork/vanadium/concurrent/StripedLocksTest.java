package com.synsenetwork.vanadium.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StripedLocksTest {
    @Test void runsTheAction() {
        StripedLocks locks = new StripedLocks(64);
        int[] ran = {0};
        locks.runLocked(123L, () -> ran[0]++);
        locks.runLocked(123L, 456L, () -> ran[0]++);
        assertEquals(2, ran[0]);
    }

    @Test void sameKeyPairDoesNotSelfDeadlock() {
        StripedLocks locks = new StripedLocks(64);
        int[] ran = {0};
        locks.runLocked(42L, 42L, () -> ran[0]++); // identical keys → same stripe → must lock once
        assertEquals(1, ran[0]);
    }

    @Test void stripeCountMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(48));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(0));
    }

    @Test void mutualExclusionOnSameKey() throws InterruptedException {
        StripedLocks locks = new StripedLocks(64);
        int threads = 8, perThread = 20_000;
        int[] counter = {0}; // plain int: only correct if runLocked really excludes
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                for (int i = 0; i < perThread; i++) {
                    locks.runLocked(7L, () -> counter[0]++);
                }
                done.countDown();
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(threads * perThread, counter[0]);
    }

    /** Two threads acquiring the same pair in opposite orders must not deadlock (ordered acquisition). */
    @Test void crossOrderPairsDoNotDeadlock() throws InterruptedException {
        StripedLocks locks = new StripedLocks(64);
        // Pick two keys guaranteed to land on different stripes.
        long keyA = 0L;
        long keyB = 1L;
        int i = 1;
        while (locks.stripeIndex(keyA) == locks.stripeIndex(keyB)) {
            keyB = ++i;
        }
        long a = keyA;
        long b = keyB;
        int iterations = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> {
            for (int n = 0; n < iterations; n++) {
                locks.runLocked(a, b, () -> {
                });
            }
            done.countDown();
        });
        tasks.add(() -> {
            for (int n = 0; n < iterations; n++) {
                locks.runLocked(b, a, () -> {
                });
            }
            done.countDown();
        });
        tasks.forEach(pool::execute);
        assertTrue(done.await(20, TimeUnit.SECONDS), "cross-order acquisition deadlocked");
        pool.shutdownNow();
    }
}

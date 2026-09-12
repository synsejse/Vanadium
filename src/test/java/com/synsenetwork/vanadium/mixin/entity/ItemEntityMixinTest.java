package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ItemEntityMixinTest {
    @Test
    void failedMergeReleasesTheLockForAnotherThread() throws Exception {
        // Exercise the wrapper itself; the live smoke test separately checks mixin application.
        var wrapper = ItemEntityMixin.class.getDeclaredMethod("tryMerge", Operation.class);
        wrapper.setAccessible(true);
        var mixin = new ItemEntityMixin();
        var failure = new IllegalStateException("merge failed");
        Operation<Void> throwing = args -> { throw failure; };
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> wrapper.invoke(mixin, throwing));
        assertSame(failure, thrown.getCause());

        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "item-merge-lock-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var result = executor.submit(() -> {
                Operation<Void> successful = args -> null;
                wrapper.invoke(mixin, successful);
                return true;
            });
            assertTrue(result.get(5, TimeUnit.SECONDS), "a failed merge must not retain the lock");
        } finally {
            executor.shutdownNow();
        }
    }
}

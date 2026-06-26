package com.synsenetwork.vanadium.client;

import com.synsenetwork.vanadium.debug.DebugFramePayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DebugFrameHolderTest {

    private static final DebugFramePayload.Stats STATS =
            new DebugFramePayload.Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0f);

    @Test
    void heldWithinTimeoutThenExpires() {
        var frame = new DebugFramePayload(8, STATS, List.of(), List.of(), List.of());
        DebugFrameHolder.set(frame, 1000L);

        assertSame(frame, DebugFrameHolder.current(1500L)); // well within the window
        assertSame(frame, DebugFrameHolder.current(4000L)); // age == 3000ms exactly, still valid
        assertNull(DebugFrameHolder.current(4001L));        // age == 3001ms, expired (debug stopped)
    }

    @Test
    void clearDropsFrameImmediately() {
        var frame = new DebugFramePayload(8, STATS, List.of(), List.of(), List.of());
        DebugFrameHolder.set(frame, 1000L);
        assertSame(frame, DebugFrameHolder.current(1000L));

        DebugFrameHolder.clear();
        assertNull(DebugFrameHolder.current(1000L));
    }
}

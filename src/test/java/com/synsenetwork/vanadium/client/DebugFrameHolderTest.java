package com.synsenetwork.vanadium.client;

import com.synsenetwork.vanadium.debug.DebugFramePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebugFrameHolderTest {

    @BeforeEach
    void reset() {
        DebugFrameHolder.set(null, 0L);
    }

    @Test
    void returnsFrameWithinTimeoutAndNullAfter() {
        var frame = new DebugFramePayload(8, List.of(), List.of(), List.of());
        DebugFrameHolder.set(frame, 1000L);

        assertSame(frame, DebugFrameHolder.current(1500L)); // within 1s
        assertNull(DebugFrameHolder.current(2500L));        // 1.5s later -> expired
        assertSame(frame, DebugFrameHolder.current(2000L)); // age == 1000ms exactly, still valid
        assertNull(DebugFrameHolder.current(2001L));        // age == 1001ms, expired
    }

    @Test
    void nullWhenNeverSet() {
        DebugFrameHolder.set(null, 0L);
        assertNull(DebugFrameHolder.current(10L));
    }
}

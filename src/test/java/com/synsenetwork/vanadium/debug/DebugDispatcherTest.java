package com.synsenetwork.vanadium.debug;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DebugDispatcherTest {

    @Test
    void recordingTracksSubscriptions() {
        var d = new DebugDispatcher(8);
        UUID a = UUID.randomUUID();
        assertFalse(d.isRecording());

        d.subscribe(a);
        assertTrue(d.isRecording());
        assertTrue(d.isSubscribed(a));

        d.unsubscribe(a);
        assertFalse(d.isRecording());
        assertFalse(d.isSubscribed(a));
    }
}

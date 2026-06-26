package com.synsenetwork.vanadium.client;

import com.synsenetwork.vanadium.debug.DebugFramePayload;

/**
 * Holds the latest debug frame received from the server. Frames arrive about once per second; the
 * timeout is generous (well above that interval, so it never blinks) but finite, so the overlay clears
 * a few seconds after frames stop — e.g. {@code /vanadium debug off}. {@link #clear()} drops it at once.
 */
public final class DebugFrameHolder {

    private static final long TIMEOUT_MS = 3000;

    private static volatile DebugFramePayload frame;
    private static volatile long receivedAtMs;

    private DebugFrameHolder() {
    }

    public static void set(DebugFramePayload frame, long nowMs) {
        DebugFrameHolder.frame = frame;
        DebugFrameHolder.receivedAtMs = nowMs;
    }

    public static void clear() {
        frame = null;
    }

    public static DebugFramePayload current(long nowMs) {
        DebugFramePayload f = frame;
        if (f == null || nowMs - receivedAtMs > TIMEOUT_MS) {
            return null;
        }
        return f;
    }
}

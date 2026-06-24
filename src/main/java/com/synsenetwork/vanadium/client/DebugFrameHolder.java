package com.synsenetwork.vanadium.client;

import com.synsenetwork.vanadium.debug.DebugFramePayload;

/** Holds the latest debug frame received from the server, expiring it shortly after frames stop. */
public final class DebugFrameHolder {

    private static final long TIMEOUT_MS = 1000;

    private static volatile DebugFramePayload frame;
    private static volatile long receivedAtMs;

    private DebugFrameHolder() {
    }

    public static void set(DebugFramePayload frame, long nowMs) {
        DebugFrameHolder.frame = frame;
        DebugFrameHolder.receivedAtMs = nowMs;
    }

    public static DebugFramePayload current(long nowMs) {
        DebugFramePayload f = frame;
        if (f == null || nowMs - receivedAtMs > TIMEOUT_MS) {
            return null;
        }
        return f;
    }

    public static long receivedAtMs() {
        return receivedAtMs;
    }
}

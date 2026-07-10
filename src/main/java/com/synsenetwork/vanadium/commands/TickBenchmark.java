package com.synsenetwork.vanadium.commands;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * A 30-second unlocked-TPS benchmark driven by vanilla's tick-sprint machinery: while sprinting the
 * server loop sets nanosPerTick to 0 (no sleep between ticks), syncs clients, and suppresses
 * overload warnings. This class starts a sprint far larger than 30s can consume, counts ticks from
 * the END_SERVER_TICK event, and stops the sprint when the wall clock runs out.
 *
 * <p>All state is confined to the Server thread (commands and END_SERVER_TICK both run there), so
 * plain fields suffice.
 */
public final class TickBenchmark {
    private static final long DURATION_NANOS = 30L * 1_000_000_000L;
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private static ServerCommandSource source;
    private static boolean active;
    private static long startNanos;
    private static long windowStartNanos;
    private static int totalTicks;
    private static int windowTicks;
    private static int peakWindowTicks;

    private TickBenchmark() {
    }

    /** Starts the benchmark; false if one is already running or the server is already sprinting. */
    public static boolean start(MinecraftServer server, ServerCommandSource commandSource) {
        if (active || server.getTickManager().isSprinting()) {
            return false;
        }
        active = true;
        source = commandSource;
        startNanos = System.nanoTime();
        windowStartNanos = startNanos;
        totalTicks = 0;
        windowTicks = 0;
        peakWindowTicks = 0;
        server.getTickManager().startSprint(Integer.MAX_VALUE);
        return true;
    }

    /** True while a benchmark runs — ServerTickManagerMixin uses this to mute vanilla's sprint report. */
    public static boolean isActive() {
        return active;
    }

    /** Registered on ServerTickEvents.END_SERVER_TICK. */
    public static void onTickEnd(MinecraftServer server) {
        if (!active) {
            return;
        }
        totalTicks++;
        windowTicks++;
        long now = System.nanoTime();
        if (now - windowStartNanos >= WINDOW_NANOS) {
            peakWindowTicks = Math.max(peakWindowTicks, windowTicks);
            windowStartNanos = now;
            windowTicks = 0;
        }
        // Finish on time, or early if something else ended the sprint (e.g. /tick sprint stop).
        if (now - startNanos >= DURATION_NANOS || !server.getTickManager().isSprinting()) {
            finish(server, now);
        }
    }

    private static void finish(MinecraftServer server, long now) {
        server.getTickManager().stopSprinting(); // while still active, so the vanilla report stays muted
        active = false;
        double seconds = (now - startNanos) / 1.0e9;
        double average = totalTicks / seconds;
        int peak = Math.max(peakWindowTicks, windowTicks);
        MutableText message = Text.literal(String.format(
                "Vanadium: benchmark — %,d ticks in %.1fs, avg %.1f TPS, peak %,d TPS (best 1s window)",
                totalTicks, seconds, average, peak));
        source.sendFeedback(() -> message, true);
        source = null;
    }
}

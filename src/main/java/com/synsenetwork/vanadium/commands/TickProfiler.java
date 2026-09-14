package com.synsenetwork.vanadium.commands;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.tick.TickProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** One bounded profile at a time, started/stopped and sampled exclusively on the server thread. */
public final class TickProfiler {
    private static TickProfile active;
    private static CommandSourceStack source;
    private static long deadline;
    private static long tickStart;
    private static String configuration;
    private static String lastReport;

    private TickProfiler() {}

    public static boolean start(CommandSourceStack commandSource, int seconds) {
        if (active != null) return false;
        active = new TickProfile();
        source = commandSource;
        deadline = System.nanoTime() + seconds * 1_000_000_000L;
        tickStart = 0;
        configuration = "workers=" + Vanadium.scheduler.workerCount() + ", initial cellSize="
                + Vanadium.scheduler.cellSize() + "; keep configuration fixed during capture";
        return true;
    }

    public static void onTickStart(MinecraftServer server) {
        if (active == null) return;
        tickStart = System.nanoTime();
        Vanadium.scheduler.setProfile(active);
    }

    public static void onTickEnd(MinecraftServer server) {
        if (active == null || tickStart == 0) return;
        long now = System.nanoTime();
        active.recordTick(now - tickStart);
        tickStart = 0;
        if (now >= deadline || active.isFull()) stop();
    }

    public static boolean stop() {
        if (active == null) return false;
        Vanadium.scheduler.setProfile(null);
        lastReport = "Vanadium profile — " + configuration + "\n" + active.report();
        CommandSourceStack recipient = source;
        active = null;
        source = null;
        tickStart = 0;
        Vanadium.LOGGER.info(lastReport);
        recipient.sendSuccess(() -> Component.literal(lastReport), false);
        return true;
    }

    public static String lastReport() {
        return lastReport == null ? "Vanadium: no completed profile for this server" : lastReport;
    }

    public static void onServerStopped(MinecraftServer server) {
        Vanadium.scheduler.setProfile(null);
        active = null;
        source = null;
        lastReport = null;
        configuration = null;
        tickStart = 0;
    }
}

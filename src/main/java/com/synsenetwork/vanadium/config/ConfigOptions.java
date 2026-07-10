package com.synsenetwork.vanadium.config;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * Single source of truth for every user-facing config option. The /vanadium command tree, tab
 * completion, status output, and defaults reset are all generated from {@link #ALL} — adding an
 * entry here is all it takes to expose a new config field.
 */
public final class ConfigOptions {
    /** One user-facing option. {@code live() == false} → changes only take effect on restart. */
    public sealed interface Option permits BoolOption, IntOption {
        String name();

        String description();

        boolean live();
    }

    public record BoolOption(String name, String description, boolean live,
                             Function<VanadiumConfig, Boolean> get,
                             BiConsumer<VanadiumConfig, Boolean> set,
                             boolean def) implements Option {
    }

    public record IntOption(String name, String description, boolean live,
                            ToIntFunction<VanadiumConfig> get,
                            ObjIntConsumer<VanadiumConfig> set,
                            int def, int min) implements Option {
    }

    public static final List<Option> ALL = List.of(
            new BoolOption("enabled", "master switch for all parallelism", true,
                    c -> c.enabled, (c, v) -> c.enabled = v, true),
            new IntOption("workers", "worker threads (<= 0 = one per core)", false,
                    c -> c.workers, (c, v) -> c.workers = v, 0, Integer.MIN_VALUE),
            new IntOption("cellSize", "parallel cell size in chunks (0 = auto)", true,
                    c -> c.cellSize, (c, v) -> c.cellSize = v, 0, 0),
            new BoolOption("parallelEntities", "tick entities in parallel", true,
                    c -> c.parallelEntities, (c, v) -> c.parallelEntities = v, true),
            new BoolOption("parallelBlockEntities", "tick block entities in parallel", true,
                    c -> c.parallelBlockEntities, (c, v) -> c.parallelBlockEntities = v, true),
            new BoolOption("parallelChunkTicks", "tick chunks (weather, random ticks) in parallel", true,
                    c -> c.parallelChunkTicks, (c, v) -> c.parallelChunkTicks = v, true),
            new BoolOption("parallelScheduledTicks", "run scheduled block/fluid ticks in parallel", true,
                    c -> c.parallelScheduledTicks, (c, v) -> c.parallelScheduledTicks = v, true),
            new BoolOption("parallelSpawning", "run per-chunk natural mob spawning in parallel", true,
                    c -> c.parallelSpawning, (c, v) -> c.parallelSpawning = v, true),
            new BoolOption("parallelTracking", "run entity tracking (packets to watchers) in parallel", true,
                    c -> c.parallelTracking, (c, v) -> c.parallelTracking = v, true),
            new BoolOption("chunkCache", "thread-safe chunk lookup cache for workers", true,
                    c -> c.chunkCache, (c, v) -> c.chunkCache = v, true),
            new BoolOption("parallelChunkLoads", "per-chunk load locks (false = one global lock)", true,
                    c -> c.parallelChunkLoads, (c, v) -> c.parallelChunkLoads = v, true));

    private ConfigOptions() {
    }

    public static Optional<Option> find(String name) {
        return ALL.stream().filter(option -> option.name().equals(name)).findFirst();
    }

    /** Resets every option on the given config to its default value. */
    public static void resetToDefaults(VanadiumConfig config) {
        for (Option option : ALL) {
            switch (option) {
                case BoolOption bool -> bool.set().accept(config, bool.def());
                case IntOption anInt -> anInt.set().accept(config, anInt.def());
            }
        }
    }
}

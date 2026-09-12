package com.synsenetwork.vanadium.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;
import com.synsenetwork.vanadium.Vanadium;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

@Config(name = "vanadium")
public class VanadiumConfig implements ConfigData {
    @Comment("Master switch: false = fully vanilla ticking (all parallelism off)")
    public boolean enabled = true;

    @Comment("Worker threads for parallel ticking. <= 0 = one per CPU core, otherwise capped at this "
            + "value (never above core count, floor 2). Takes effect on restart.")
    @RestartRequired
    public int workers = 0;

    @Comment("Width/height, in chunks, of each parallel cell. 0 = auto (chosen from CPU core count). "
            + "Smaller cells = finer parallelism but more overhead. Applies next tick.")
    @Min(0)
    public int cellSize = 0;

    @Comment("Tick entities in parallel")
    public boolean parallelEntities = true;

    @Comment("Tick block entities in parallel")
    public boolean parallelBlockEntities = true;

    @Comment("Entity type IDs to tick on the server thread (namespace:type). Empty list disables rules.")
    public List<String> serialEntityTypes = new ArrayList<>();

    @Comment("Block entity type IDs to tick on the server thread (namespace:type). Empty list disables rules.")
    public List<String> serialBlockEntityTypes = new ArrayList<>();

    private transient TypeRules entityRules = new TypeRules();
    private transient TypeRules blockEntityRules = new TypeRules();

    public boolean isSerialEntity(Identifier id) {
        return entityRules.contains(id);
    }

    public boolean isSerialBlockEntity(Identifier id) {
        return blockEntityRules.contains(id);
    }

    @Comment("Tick chunks (weather, random ticks) in parallel")
    public boolean parallelChunkTicks = true;

    @Comment("Run scheduled block and fluid ticks (redstone, fluid spread, leaf decay) in parallel")
    public boolean parallelScheduledTicks = true;

    @Comment("Run per-chunk natural mob spawning in parallel")
    public boolean parallelSpawning = true;

    @Comment("Run entity tracking (movement/data packets to watching players) in parallel")
    public boolean parallelTracking = true;

    @Comment("Batch each connection's packets into one flush per tick instead of one per packet")
    public boolean consolidateFlushes = true;

    @Comment("Thread-safe chunk lookup cache for worker threads (disabling costs performance)")
    public boolean chunkCache = true;

    @Comment("Per-chunk load locks so different chunks can load in parallel; false = one global lock")
    public boolean parallelChunkLoads = true;

    /** Refresh once per tick so in-place list edits never add scans to individual ticker lookups. */
    public void refreshSerialRules() {
        entityRules.update(serialEntityTypes);
        blockEntityRules.update(serialBlockEntityTypes);
    }

    @Override
    public void validatePostLoad() throws ValidationException {
        if (cellSize < 0) {
            throw new ValidationException("cellSize must be >= 0 (0 = auto) (got " + cellSize + ").");
        }
        try {
            refreshSerialRules();
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    /** Worker count from config: {@code <= 0} = one per core; otherwise capped at core count, floor 2. */
    public static int resolveWorkers() {
        return resolveWorkers(Vanadium.config.workers, Runtime.getRuntime().availableProcessors());
    }

    static int resolveWorkers(int workers, int cores) {
        return workers <= 0 ? cores : Math.clamp(workers, 2, Math.max(2, cores));
    }

    /** The cell size to use: the explicit config value, or the core-count heuristic when 0 (auto). */
    public static int resolveCellSize() {
        int configured = Vanadium.config.cellSize;
        return configured > 0 ? configured : autoCellSize(resolveWorkers());
    }

    /**
     * Heuristic cell size from core count: aim for enough cells to feed every worker thread across
     * the four color waves over a typical ticking area. Clamped to a sane range.
     */
    static int autoCellSize(int parallelism) {
        int colors = 4;
        double referenceAreaChunks = 441.0; // ~ simulation distance 10
        int size = (int) Math.round(Math.sqrt(referenceAreaChunks / (colors * Math.max(1, parallelism))));
        return Math.clamp(size, 2, 8);
    }
}

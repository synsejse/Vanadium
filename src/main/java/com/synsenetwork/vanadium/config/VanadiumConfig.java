package com.synsenetwork.vanadium.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;
import net.minecraft.resources.Identifier;
import com.synsenetwork.vanadium.Vanadium;
import java.util.ArrayList;
import java.util.List;

@Config(name = "vanadium")
public class VanadiumConfig implements ConfigData {
    @Comment("Master switch: false = fully vanilla ticking (all parallelism off)")
    public boolean enabled = true;

    @Comment("Worker threads for parallel ticking. <= 0 = available logical processors. Positive values "
            + "are clamped between 2 and max(2, available logical processors). Takes effect on restart.")
    @RestartRequired
    public int workers = 0;

    @Comment("Width/height, in chunks, of each parallel cell. 0 = auto (chosen from resolved worker count). "
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

    @Comment("Warn about waves exceeding this many milliseconds. 0 disables diagnostics; live next tick.")
    @Min(0)
    public int slowWaveMillis = 0;

    @Comment("Include current task/type labels in slow-wave reports. Adds per-task overhead while diagnostics are enabled.")
    public boolean detailedTickDiagnostics = false;

    private transient TypeRules entityRules = new TypeRules();
    private transient TypeRules blockEntityRules = new TypeRules();

    public boolean isSerialEntity(Identifier id) {
        return entityRules.contains(id);
    }

    public boolean isSerialBlockEntity(Identifier id) {
        return blockEntityRules.contains(id);
    }

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
        if (slowWaveMillis < 0) throw new ValidationException("slowWaveMillis must be >= 0");
        try {
            refreshSerialRules();
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    /** Auto uses available logical processors; explicit values clamp to 2..max(2, available processors). */
    public static int resolveWorkers() {
        return resolveWorkers(Vanadium.config.workers, Runtime.getRuntime().availableProcessors());
    }

    static int resolveWorkers(int workers, int availableProcessors) {
        return workers <= 0 ? availableProcessors : Math.clamp(workers, 2, Math.max(2, availableProcessors));
    }

    /** The cell size to use: the explicit config value, or the worker-count heuristic when 0 (auto). */
    public static int resolveCellSize() {
        int configured = Vanadium.config.cellSize;
        return configured > 0 ? configured : autoCellSize(resolveWorkers());
    }

    /**
     * Heuristic cell size from resolved worker count: aim for enough cells to feed every worker thread across
     * the four color waves over a typical ticking area. Clamped to a sane range.
     */
    static int autoCellSize(int parallelism) {
        int colors = 4;
        double referenceAreaChunks = 441.0; // ~ simulation distance 10
        int size = (int) Math.round(Math.sqrt(referenceAreaChunks / (colors * Math.max(1, parallelism))));
        return Math.clamp(size, 2, 8);
    }
}

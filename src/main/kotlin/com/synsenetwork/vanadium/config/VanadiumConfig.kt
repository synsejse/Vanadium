package com.synsenetwork.vanadium.config

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.IdleWorkers
import com.synsenetwork.vanadium.tick.RegionGrid
import me.shedaniel.autoconfig.ConfigData
import me.shedaniel.autoconfig.annotation.Config
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment
import net.minecraft.resources.Identifier

/** TOML-backed options (`config/vanadium.toml`). Fields are public for AutoConfig and the Java mixins. */
@Config(name = "vanadium")
class VanadiumConfig : ConfigData {
    @field:Comment("Master switch: false disables parallel ticking and optional optimizations; structural mixins, locks and loaded-chunk lookups remain active")
    @JvmField var enabled = true

    @field:Comment("Worker threads for parallel ticking. <= 0 = one per physical core this server may use, leaving one "
            + "logical processor for the server thread, which works alongside them (SMT siblings get no worker); "
            + "two thirds of the logical processors where the core count cannot be read. Positive values are "
            + "clamped between 2 and max(2, available logical processors). Takes effect on restart.")
    @JvmField var workers = 0

    @field:Comment("Minimum distance, in chunks, between block-entity, chunk, scheduled-tick and spawning work that may "
            + "run at the same time (1 to 16). Regions adapt to the world automatically; this only sets the safety "
            + "margin. Entities are not affected: they tick everywhere at once. Applies next tick.")
    @JvmField var isolation = 2

    @field:Comment("Tick entities in parallel: all of them in one wave, wherever they are. Each owns itself while it "
            + "ticks, and what entities do to each other is locked per interaction.")
    @JvmField var parallelEntities = true

    @field:Comment("When an entity's tick throws during parallel entity ticking, log it and skip the rest of that tick "
            + "instead of stopping the server. An entity that fails 3 ticks in a row still stops it. false = vanilla behaviour.")
    @JvmField var skipFailedEntityTicks = true

    @field:Comment("Tick block entities in parallel")
    @JvmField var parallelBlockEntities = true

    @field:Comment("Entity type IDs to tick on the server thread (namespace:type). Empty list disables rules.")
    @JvmField var serialEntityTypes: MutableList<String> = ArrayList()

    @field:Comment("Block entity type IDs to tick on the server thread (namespace:type). Empty list disables rules.")
    @JvmField var serialBlockEntityTypes: MutableList<String> = ArrayList()

    @field:Comment("Tick chunks (weather, random ticks) in parallel")
    @JvmField var parallelChunkTicks = true

    @field:Comment("Run scheduled block and fluid ticks (redstone, fluid spread, leaf decay) in parallel")
    @JvmField var parallelScheduledTicks = true

    @field:Comment("Run per-chunk natural mob spawning and the mob count in parallel. On dedicated servers, also skip the "
            + "mob count while spawn_mobs is false until something reads it (debug report, mods)")
    @JvmField var parallelSpawning = true

    @field:Comment("Run entity tracking (movement/data packets to watching players) in parallel")
    @JvmField var parallelTracking = true

    @field:Comment("Prepare chunk section packet data in parallel between world ticks")
    @JvmField var parallelChunkPackets = true

    @field:Comment("Batch each connection's packets into one flush per tick instead of one per packet")
    @JvmField var consolidateFlushes = true

    @field:Comment("Index crowded entity sections by cell so pushing and small box queries test only nearby entities")
    @JvmField var crowdedSectionIndex = true

    @field:Comment("What worker threads and the server thread do between ticks. lean (default): nothing spins, the "
            + "least CPU. balanced: workers park and the server thread spins until the next tick, about one core "
            + "while the world has parallel work. hot: workers spin too; on a 72-thread Xeon VM every worker that "
            + "ran then holds a core (24 workers: 24 cores, against 11-25% each otherwise), and it halved 20 TPS "
            + "tick times at times when idle threads were slow, with little or no gain otherwise. Compare the modes "
            + "on your own machine with /vanadium profile. Live next tick.")
    @JvmField var idleWorkers = "lean"

    @field:Comment("Warn about waves exceeding this many milliseconds. 0 disables diagnostics; live next tick.")
    @JvmField var slowWaveMillis = 0

    @field:Comment("Include current task/type labels in slow-wave reports. Adds per-task overhead while diagnostics are enabled.")
    @JvmField var detailedTickDiagnostics = false

    @Transient private val entityRules = TypeRules()
    @Transient private val blockEntityRules = TypeRules()

    fun isSerialEntity(id: Identifier): Boolean = id in entityRules

    fun isSerialBlockEntity(id: Identifier): Boolean = id in blockEntityRules

    fun hasSerialEntityRules(): Boolean = !entityRules.isEmpty()

    /** Identity changes whenever the entity rules do, including across config reloads. */
    fun serialEntityRulesKey(): Any = entityRules.key

    fun hasSerialBlockEntityRules(): Boolean = !blockEntityRules.isEmpty()

    /** [idleWorkers] as a mode. Parsed once per tick; the field is validated on load and by `set`. */
    fun idleWorkersMode(): IdleWorkers = requireNotNull(IdleWorkers.parse(idleWorkers)) {
        "idleWorkers must be one of ${IdleWorkers.names.joinToString()} (got '$idleWorkers')."
    }

    /** Refresh once per tick so in-place list edits never add scans to individual ticker lookups. */
    fun refreshSerialRules() {
        entityRules.update(serialEntityTypes)
        blockEntityRules.update(serialBlockEntityTypes)
    }

    override fun validatePostLoad() {
        if (isolation !in 1..RegionGrid.MAX_ISOLATION) {
            throw ConfigData.ValidationException("isolation must be within 1..${RegionGrid.MAX_ISOLATION} (got $isolation).")
        }
        if (slowWaveMillis < 0) throw ConfigData.ValidationException("slowWaveMillis must be >= 0")
        if (IdleWorkers.parse(idleWorkers) == null) {
            throw ConfigData.ValidationException("idleWorkers must be one of ${IdleWorkers.names.joinToString()} (got '$idleWorkers').")
        }
        try {
            refreshSerialRules()
        } catch (e: IllegalArgumentException) {
            throw ConfigData.ValidationException(e.message)
        }
    }

    companion object {
        /**
         * Auto uses one worker per system physical core, and leaves one available processor for the server
         * thread, which joins every wave: min(cores, available processors - 1), at least one. With SMT the server
         * thread takes a spare sibling and every core keeps a worker; without SMT it takes a core. Workers beyond
         * the cores share them with SMT siblings, mostly spin, and measured slower (48 of 72 threads: -11% in
         * combat). A CPU quota (cgroup `cpu.max`, `-XX:ActiveProcessorCount`) lowers the available processors and
         * caps the count the same way. Where the topology is unknown, auto keeps two thirds of the available
         * processors. Explicit values clamp to 2..max(2, available processors).
         */
        @JvmStatic
        fun resolveWorkers(): Int {
            val workers = Vanadium.config.workers
            return resolveWorkers(workers, Runtime.getRuntime().availableProcessors(), if (workers <= 0) CpuTopology.cores else null)
        }

        /** How `workers = 0` was resolved, for the startup log. */
        @JvmStatic
        fun describeAutoWorkers(): String {
            val processors = Runtime.getRuntime().availableProcessors()
            val cores = CpuTopology.cores ?: return "two thirds of $processors processors; core topology unknown"
            return "$cores system cores; $processors JVM processors"
        }

        /** [cores]: system physical cores; [availableProcessors] applies the JVM's process/quota limit. */
        internal fun resolveWorkers(workers: Int, availableProcessors: Int, cores: Int?): Int = when {
            workers > 0 -> workers.coerceIn(2, maxOf(2, availableProcessors))
            cores == null -> maxOf(1, availableProcessors * 2 / 3)
            else -> maxOf(1, minOf(cores, availableProcessors - 1))
        }
    }
}

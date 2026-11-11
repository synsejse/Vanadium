package com.synsenetwork.vanadium.config

import com.synsenetwork.vanadium.Vanadium
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

    @field:Comment("Worker threads for parallel ticking. <= 0 = available logical processors. Positive values "
            + "are clamped between 2 and max(2, available logical processors). Takes effect on restart.")
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

    @field:Comment("Run per-chunk natural mob spawning in parallel")
    @JvmField var parallelSpawning = true

    @field:Comment("Run entity tracking (movement/data packets to watching players) in parallel")
    @JvmField var parallelTracking = true

    @field:Comment("Prepare chunk section packet data in parallel between world ticks")
    @JvmField var parallelChunkPackets = true

    @field:Comment("Batch each connection's packets into one flush per tick instead of one per packet")
    @JvmField var consolidateFlushes = true

    @field:Comment("Per-chunk load locks so different chunks can load in parallel; false = one global lock")
    @JvmField var parallelChunkLoads = true

    @field:Comment("Warn about waves exceeding this many milliseconds. 0 disables diagnostics; live next tick.")
    @JvmField var slowWaveMillis = 0

    @field:Comment("Include current task/type labels in slow-wave reports. Adds per-task overhead while diagnostics are enabled.")
    @JvmField var detailedTickDiagnostics = false

    @Transient private val entityRules = TypeRules()
    @Transient private val blockEntityRules = TypeRules()

    fun isSerialEntity(id: Identifier): Boolean = id in entityRules

    fun isSerialBlockEntity(id: Identifier): Boolean = id in blockEntityRules

    fun hasSerialEntityRules(): Boolean = !entityRules.isEmpty()

    fun hasSerialBlockEntityRules(): Boolean = !blockEntityRules.isEmpty()

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
        try {
            refreshSerialRules()
        } catch (e: IllegalArgumentException) {
            throw ConfigData.ValidationException(e.message)
        }
    }

    companion object {
        /** Auto uses available logical processors; explicit values clamp to 2..max(2, available processors). */
        @JvmStatic
        fun resolveWorkers(): Int = resolveWorkers(Vanadium.config.workers, Runtime.getRuntime().availableProcessors())

        internal fun resolveWorkers(workers: Int, availableProcessors: Int): Int =
            if (workers <= 0) availableProcessors else workers.coerceIn(2, maxOf(2, availableProcessors))
    }
}

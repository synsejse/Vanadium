# Architecture and investigation notes

Vanadium moves parts of vanilla server ticking onto a shared worker pool. It is not a
server-per-region architecture: worlds still advance one at a time, and the server
thread gathers work and waits at explicit barriers.

## Scheduling

Each server startup creates its own `WorkerPool` and `TickScheduler`. Each stage has a
reusable `RegionGrid`, which records tasks per chunk in enqueue order and evicts chunks idle for
more than 100 stage begins.

**Isolation.** Work in chunks within `isolation` chunks of each other (Chebyshev distance;
default 2) never runs at the same time. This is the only spatial safety rule, and it does not
depend on worker count or region shape.

**Adaptive regions.** The grid plans regions from where the work is. Chunks live in dense 16×16
tiles; each active tile splits like a quadtree while a square holds more tasks than the target,
`max(tasks / (participants × 4), smallest region worth claiming)`. The smallest worthwhile
region is about 25 µs of work, estimated from the time participants actually spent running the
previous waves' tasks. Crowded areas therefore become single-chunk regions, while quiet areas and
cheap stages (random ticks) stay merged. Square weights come from per-tile prefix sums. A region
is the bounding box of its chunks, and its tasks keep their enqueue order. Two regions conflict
when their boxes are within `isolation` of each other; boxes are stamped into their tile and the
ring around each box is scanned, which makes the relation symmetric by construction.

Planning a crowded stage costs hundreds of microseconds on the server thread, mostly cache misses,
so a plan is reused while every active chunk still belongs to one of its regions: for up to 20
waves, while the task count stays within a quarter of the planned one, and while the region-size
target stays within a factor of two. Conflicts are defined on boxes, so a reused plan is exactly as
safe as a fresh one; regions whose chunks went quiet run empty.

**Waves.** Regions are ordered heaviest first and coloured greedily so conflicting regions never
share a colour. The scheduler picks the wave from the measured work per region:

- `ClaimWave` (at least 100 µs per region, typically entities): a region starts whenever none of
  its conflicts is running. A claimant moves its region to TRYING and inspects its conflicts; a
  RUNNING one makes it back off, and between two TRYING ones the higher index backs off while the
  lower waits. All state accesses are volatile, so two conflicting claimants cannot both pass, and
  waits only point to higher indices. Claims first read the conflicts and write nothing if one is
  busy, and a participant whose scan claims nothing spins on the completion count rather than
  rescanning. Every back-off chain ends at a region that runs, so a completion is always coming.
- `DataflowWave` (cheaper regions, typically random ticks): a region runs once all its
  lower-coloured conflicts have finished, so conflicting regions are always ordered and the order
  cannot cycle. Ready regions are published into a slot array; each becomes ready once, so a
  participant takes the next slot with one atomic increment and a completion only decrements its
  successors. Nothing scans or retries.

Neither wave has stage-wide barriers between groups of regions. Failures are collected, every
region still completes or releases its successors, and the first failure is rethrown after the
wave. Order between conflicting regions can change between ticks.

**Why this shape.** Captured per-chunk work replayed offline, then checked in one 26.3 server JVM
(32 workers, 4,096 zombies, isolation 2): finer regions shortened the entity wave around clustered
farms by about 8% and changed nothing on a uniform crowd; claiming finished heavy entity regions
about 6% sooner than dataflow ordering, while dataflow ran cheap random-tick waves about twice as
fast as claiming, whose scans grow with region count. Crowds whose chunks all lie within the
isolation distance (mob farms) are inherently serial: no region shape can run them in parallel.

The final split factor of four retained uniform-world throughput and improved the clustered
fixture by about 11% against the fixed grid. See the [26.3 A/B results](benchmarks/ADAPTIVE_GRID_26_3.md)
for all samples, configuration and limitations.

`WorkerPool.runWave()` claims tasks from an indexed list on both persistent pool threads
and its caller. The caller publishes a private `WorkBatch` through one volatile field; idle
workers spin briefly (`vanadium.workerSpinMicros`, default 2,000) before parking, and a
parked worker is unparked only when a wave needs it, using a Dekker-style parked flag.
The longer spin trades additional CPU for lower wake-up latency; it is bounded even between ticks.
Low-numbered workers take small waves. Tasks are claimed in chunks once they greatly
outnumber participants, and each participant subtracts its completed count once, so
per-task work does not bounce shared counters between sockets. The caller spins
(`vanadium.callerSpinMicros`, default 200) and then parks for the tail. The scheduler keeps the
list stable until every task finishes; late helpers only check the saved count, allowing
the caller to reuse the list after the barrier. Other collection types are copied to an
array-backed list. An interrupted caller still waits for all tasks and restores its interrupt
flag before returning or propagating a failure. A one-task wave runs directly on the caller.
This is why unconditional tick-path synchronization matters.

The actual stage timing comes from Minecraft mixin injection points, not the enum alone:

| Work | Main integration points |
| --- | --- |
| Scheduled block/fluid ticks | `ServerLevelMixin.dispatchScheduledTicks` collects then drains each vanilla scheduler |
| Natural spawning and random/weather chunk ticks | `ServerChunkCacheMixin` drains spawning before random ticks, then random ticks before custom spawning/broadcasts |
| Entity tracking | `ChunkMapMixin` wraps tracking dispatch and its barrier |
| Entities | `ServerLevelMixin` queues vanilla's whole per-entity body (despawn check, range check, tick); `LevelMixin` drains before block-entity bookkeeping |
| Block entities | `LevelMixin` queues supported tick invokers and drains at the end |

Large populations are collected in two phases (`BatchEnqueue`): the server thread only records
entities (or ticking chunk holders), workers map contiguous slices to a chunk key and task, and
the server thread then enqueues the results in recording order, so per-chunk enqueue order is
unchanged. This keeps the server thread from dereferencing thousands of objects last written by
workers on other cores. Serial-rule entities still run inline, in order, before the wave.

Projectiles and entities currently in portals retain serial entity ticking. A serial rule
on any passenger routes the entire vehicle tree through the serial path. Stage flags
and `enabled` select fallback paths, but mixins and structural synchronization remain installed.
Block-entity registrations enter a concurrent queue and are merged into the active list on the
server thread after the ENTITY barrier. The BLOCK_ENTITY barrier runs before the ticking flag clears.

Exact-ID serial rules also route selected entity and block-entity tickers through the inline
server-thread path, before the corresponding wave. Rule lists are validated after config
loading. At each tick start, changed lists are compiled into ID sets;
individual ticker lookups do not scan the configured lists.

`TickProfiler` attaches an optional `TickProfile` to the scheduler from a server-tick start
event. It records tick work at the end event and stage execution around each scheduler drain.
The pool reports the caller's tail wait after it finishes claiming work; this is distinct
from total stage duration and is not a measurement of lock contention. Region/task counts
are read on the server thread while wave lists are stable. There are no worker-side profile
counters or per-task timers in normal operation. The server thread emits JFR events per tick,
stage wave and preparation run (`TickEvents.kt`); they are inert without a recording.
Profiles also record each wave's wall time times its participants, so per-stage utilization is
region time over that capacity, and the tick split is stage waves, preparation and serial rest. Opt-in profiles time each region into a
separate array slot, then aggregate samples on the caller after the barrier. Per-world
preparation intervals include collection and inline fallbacks, and world totals overlap
stage timings. Spawning/thunder and random/precipitation work have separate stage entries.
Capture duration is 1–300 seconds, with at most 65,536 stored
samples per series; reaching the tick sample limit also finishes the capture. Stage series
that reach their cap retain totals and label their percentile truncation. Server stop clears
active/previous captures and releases the command source.

`WaveDiagnostics` is an optional daemon watchdog, created for each server lifecycle and
closed at server stop. The scheduler publishes one watched wave with its dimension/stage
context. Caller and workers publish/remove their current region around execution; detailed
mode also updates a volatile task label. The watchdog reads this progress map and thread
stacks without acquiring world or scheduler monitors. Reports are bounded and rate-limited,
and completed waves are detached in `finally`, including failures. Task completion barriers
and interruption behavior are unchanged. No monitoring thread is started while disabled.

## Shared state and compatibility

Scheduled ticks remain in an ordered, identity-indexed pending queue until a callback claims
them under the vanilla scheduler monitor. Pending queries and area copying retain their
bookkeeping during the wave, and area clearing cancels unclaimed callbacks. Collection,
claiming, and final cleanup hold the monitor; execution and the completion barrier do not.
Block events retain vanilla's ordered deduplication under a separate set monitor.

Each level's `random` is a `PerThreadRandomSource`: every thread draws from its own
unsynchronized stream (seeded from the level seed and thread ID). Vanilla's single
`LegacyRandomSource` became one contended monitor under parallel random ticks, scheduled ticks
and AI. Random tick positions (`getBlockRandomPos`) also come from the calling thread's stream
instead of the shared LCG field, so no world-wide lock remains on the random-tick path. The
level random never offered a reproducible shared sequence (its seed is unique per instance).

Isolation keeps concurrent regions apart; it does not bound every possible interaction.
Vanadium also replaces collections, adds monitors, uses striped entity locks, and stamps
`ACC_SYNCHRONIZED` on selected vanilla instance methods through `SynchronisePlugin`.
Changing a mixin ordinal, lock scope, or collection view can change correctness even when
the scheduler tests pass.

### Chunk infrastructure

| Component | Responsibility |
| --- | --- |
| `ParallelChunkCache` | Extends vanilla's `ServerChunkCache`; serves loaded full chunks and coordinates synchronous misses. |
| `LevelChunkMixin` | Publishes/removes full chunks at `LevelChunk.setLoaded` on the server or C2ME thread. |
| `ChunkLockTable` | Reserves per-position reentrant load locks; owns no executors or chunks. |
| `ServerChunkCacheMixin` | Integrates tick stages and adapts vanilla lookup thread checks/cache writes. |
| `ChunkTickBatch` / `ChunkTickSink` | Collect ticking holders for parallel random/precipitation work; own no loading policy. |
| Vanilla / C2ME | Own holders, tickets, generation futures, promotion and unload decisions. |

The loaded-chunk index is a `ReadOptimizedLongMap`: reads take no lock and allocate nothing;
writes serialize. It is maintained unconditionally, including with `enabled=false`; there is
no `chunkCache` switch. It indexes chunks between `setLoaded(true)` and `setLoaded(false)`,
not requested statuses or cached load results. Conditional removal by instance prevents an
old chunk's unload from removing its replacement. `getChunkNow` reads this index only, so
its publication boundary can briefly differ from vanilla holder readiness.

FULL `getChunk` hits use the index. Misses and other statuses use vanilla/C2ME under a load
lock: `parallelChunkLoads=true` selects one lock per packed chunk position; false selects
the cache instance monitor. Both modes recheck FULL publication after acquiring the lock,
because `setLoaded` publishes before a load completes. Null and non-FULL results are never
added to the index. `hasChunk` answers true for indexed full chunks and otherwise retains
vanilla's ticket-level answer, which can be true before a full chunk is loaded.

Minecraft Main-worker requests are sent to the server thread before any lookup. These
workers are identified by their owning pool, `Util.backgroundExecutor().service()`, rather
than thread names. Ordinary Vanadium helpers use the lookup/load path directly. The loaded
index avoids vanilla's shared four-entry cache on FULL hits. The pinned C2ME chunk-system
rewrite also shortcuts some off-thread holder hits; remaining misses and status requests can
still enter vanilla's cache code. Per-position locks do not protect that cache across different
positions, and synchronizing writes alone does not make its parallel-array reads atomic.
Auditing/replacing that remaining cache path is separate follow-up work.

Each `ParallelChunkCache` owns its load-lock maintenance executor, which evicts unreserved
entries every thirty seconds. Reservations and eviction are atomic per key. Closing first
finishes vanilla saving/close, then closes maintenance, clears the loaded index and evicts
idle load locks, including when vanilla close throws. C2ME remains responsible for its own
executors; this layer does not replace asynchronous generation or ticket scheduling.

### Other shared state

Server shutdown closes the watchdog and worker pool after the final wave and clears
the scheduler, profiler, and benchmark command references. Starting another integrated
server creates fresh scheduling state and resolves the configured worker count again.

In 26.3, `TicketStorage` owns the tickets formerly held by the simulation tracker and is
synchronized through `SyncAllMixin`. `SavedDataStorageMixin` makes the cache a
`ConcurrentHashMap` with lock-free hits: `ServerLevel.getWorldBorder()` resolves saved data on
every entity move. Misses, writes and save snapshot creation share one monitor, while
asynchronous disk writes and save completion waits remain outside it. `WorldBorder` is no longer
synchronized; its one-time `applyInitialSettings` is double-checked, and workers read border
state that the server thread writes.

`PathTypeCache` (one per level, used by every pathfinding mob) stores immutable
`(position, type)` entries in a single slot array instead of vanilla's two parallel arrays, so
readers see a complete entry or a miss without a lock. The POI village distance tracker's level
map is concurrent, and `sectionsToVillage` skips the synchronized update pass when no update
is pending. `SynchronisePlugin` leaves volatile-only readers such as
`DynamicGraphMinFixedPoint.hasWork` unsynchronized.

`EntitySectionStorage` keeps every section in an `EntitySectionIndex`: a `ReadOptimizedLongMap`
of sections plus, per chunk column, an immutable array of section Ys. Every AI entity query runs
`forEachAccessibleNonEmptySection`; lookups take no lock and box nothing, and a box query visits
only the columns inside the box, where vanilla's AVL set walked every section at each section X.
Visit order matches vanilla's sorted keys (X, then Z and Y in unsigned packed order). Section
creation and removal serialize on the index. `GoalSelector` keeps vanilla's ordered set: goals
belong to one mob, which only its own region ticks.

Chunk simulation levels and POI village distances, which workers read on every entity tick, are
`ConcurrentLong2ByteMap`s: primitive open addressing whose slots are published FULL with release
semantics and only tombstoned until a rebuild, so reads take no lock and box nothing.
`DynamicGraphMinFixedPoint` itself keeps vanilla's `computedLevels` map and priority queue: every
instance method that touches them is already synchronized by `SynchronisePlugin`.

`ClassInstanceMultiMap` keeps copy-on-write per-class lists and returns one cached read-only view
per class, so a repeated `find` neither locks nor allocates. Adds, removes and the first lookup of a
class serialize, so a list built for a new class cannot miss an entity added concurrently.
`ServerLevel.getNoiseBiome` reads loaded full chunks through the lock-free chunk map; vanilla's
`BIOMES`-status request went through the load lock and the synchronized four-entry cache, and
returned an `ImposterProtoChunk` that delegates biome reads to the same full chunk. `Profiler.get`
skips its thread-local on workers, which never have an active profiler.

`Long2ObjectConcurrentHashMap` stores packed position keys
through murmur3's bijective finalizer (`MixedLongKeys`). Under `Long.hashCode`, packed chunk
keys hash to `x ^ z`, so a 32×32 area collapsed onto 32 hashes and degraded the backing
`ConcurrentHashMap` into tree bins; key and entry views invert the mixing. The concurrent maps
extend fastutil's `Abstract*Map` classes, which derive key and value views from one entry-set
view; single-key operations, including the compute family, delegate atomically to the backing map.
`ConcurrentLongLinkedOpenHashSet` must subclass vanilla's field type but never uses the
superclass storage, so it overrides every method that would read it, including `forEach` and
`spliterator` (`SectionStorage.flushAll` saves dirty POI chunks through `forEach`; earlier versions
iterated the empty superclass table there and dropped pending POI writes on save-all flush and
shutdown). Its iteration order is ascending, not insertion order.
`ServerChunkCache` also has a concurrent set for pending chunk broadcasts.

`ChunkHolder` keeps vanilla's section update sets. A per-holder monitor protects block/light
update registration and broadcasting, including set creation, clearing, and pending flags.
Replacing only an array entry is insufficient: `blockChanged` adds the first position
through a local set reference, which must remain the same object that broadcasting reads.

Chunk dirty notifications enter a concurrent queue. The server thread drains them into
`ChunkMap.chunksToEagerlySave` at chunk-map maintenance and eager-save entry points. This
preserves vanilla's ordered, deduplicated save set and C2ME's idle autosave iteration;
workers never mutate that set or wait for disk work while marking a chunk dirty.

The entity lookup exposes a live read-only Collection view, preserving membership and
iteration semantics while allowing constant-time sizing. C2ME wraps that view in an unsized
filter (entities in simulated chunks); spawn preparation unwraps it reflectively and applies
the same predicate inside each parallel batch. Spawn preparation counts views
below 1,024 entities inline without a snapshot. Larger views are copied directly into a
sized snapshot; unsized iterables retain a single-pass snapshot fallback. Parallel work
uses contiguous batches with independent vanilla spawn states and local-cap calculators.
In 26.3, vanilla counting reads spawn costs from the level's environment attributes. A scoped
thread-local entity slice replaces only `createState`'s input iteration; vanilla retains its
level-aware counting and cost logic. The override is removed/restored in `finally`.
The caller combines category counts, local player caps, and potential charges before
spawning begins. Charge order follows the original
entity order. This does not change the later shared spawn-attempt accounting policy.

Item merging locks the two participating items in entity-ID order and rechecks merge
eligibility under those locks. Mob pickup (base and existing specialized overrides), player
pickup, and hopper collection use the same per-item lock. Independent item interactions
can proceed concurrently; every wrapper releases locks in `finally`. This does not establish
thread safety for arbitrary modded item mutation or shared inventories. Neighbor-update
chains retain their world-wide monitor; vanilla's ArrayList is sufficient inside that
monitor, so the redundant copy-on-write replacement was removed. Region-owned propagation
remains architectural follow-up work.

`AreaMap` and `NearbyTrackers` index nearby tracking relationships to avoid scanning all
players for every entity. Fresh trackers stay in an identity-keyed linked map holding their
arrival ticks; this preserves FIFO migration after 200 ticks while allowing direct removal.
Area updates paint and erase the non-overlapping strips of the old and new squares, skipping
unchanged coverage. Disjoint teleports still update both complete squares. Per-tracker state
(previous position identity, painted chunk, moved flag, slot) lives on the tracker's task
object; tasks keep stable slots in an array rebuilt after membership changes. Move detection
scans contiguous slices of that array, in parallel above 1,024 trackers, and only trackers
that crossed a chunk are repainted by the caller. Vanilla's `lastSectionPos` is updated
without allocation unless the section changed.
Tracking, chunk transitions, and packet flush consolidation need
live multiplayer coverage; unit scheduler tests do not exercise them.

Tracking preparation partitions visibility diffs by player once at least two players and
1,024 candidate/known comparisons are present. Smaller workloads run inline through the
same preparation path. Each player owns a reusable byte array of action flags, indexed by
tracker slots assigned on the caller. Rows have separate writers, and slots stay stable
through preparation and tracking barriers. The caller queues one task per active tracker;
that task reads the completed rows in player order, sends entry changes once, then applies
the appropriate remove/update callbacks. There are no per-pair records or serial object
merge. Buffers grow geometrically and clear the active prefix on reuse; byte storage is
proportional to player count times peak tracker count. Player departure releases its row.
Workers in preparation never invoke callbacks or acquire the nearby-index monitor held
by the caller. The subsequent tracking wave owns all callback writes per tracker.

`NavigationIndex` conservatively indexes vanilla navigation invalidation ranges. Block
changes still call vanilla's exact predicate on the candidates. Membership changes, movement,
path replacement, recomputation and navigation ticks mark mobs dirty; dirty mobs remain
candidates for every block change until the next world-tick boundary refresh. Marking is
lock-free, since every moving mob does it every tick: a mob's first mark after a refresh flips
its own dirty flag and appends it to one of 64 thread-striped lock-free queues; later marks
only read the flag. Refresh processes only entries queued before it starts (it appends a
marker to each non-empty stripe), clears each mob's flag before snapshotting its path, and
reads navigation state outside the index monitor, so a concurrent change re-queues the mob
and keeps its fallback. If most paths keep changing
and the previous tick had fewer than two queries, refresh defers rebuilding and retains
the full dirty fallback; frequent edits or stable pending paths resume indexing. Candidate
queries copy the nearby set directly when no fallback entries need deduplication.
Custom predicate overrides and paths longer than 256 remaining nodes use the full
fallback. Mods mutating path internals outside normal navigation entry points require explicit
integration. This is a candidate filter, not asynchronous pathfinding or delayed recomputation.

C2ME provides parallel generation/loading and bundles MixinSquared. `compat/C2ME.java`
cancels two named C2ME thread-detection mixins. `fabric.mod.json` requires C2ME and rejects
Lithium and VMP. The Gradle development tasks pin and checksum-verify a 26.3 C2ME release without
flattening its nested mods into the compilation classpath.

Chunk sending prepares section byte buffers on the shared pool after vanilla/C2ME select
a player's batch, while simulation is at its between-ticks boundary. Batches below four
chunks stay inline; preparation retains at most 64 chunks per batch. The caller constructs
the packets using those buffers, including vanilla heightmaps, block-entity update tags and
light snapshots, then sends them in the original order. Batch quotas/acknowledgments and
debug tracking remain vanilla/C2ME-owned. A scoped thread-local releases prepared data on
normal completion or exceptions. `parallelChunkPackets` and the master flag select the
vanilla path. Arbitrary asynchronous chunk mutation by other mods is not supported.

## Follow-up areas for correctness work

These are source-inspection leads, not a completed concurrency audit:

- Full integrated-server join/leave cycles still need interactive coverage. Lifecycle
  callback reinitialization and dedicated-server resource shutdown have automated coverage.
- The 1.21.1 source audit in `archive/TICK_COVERAGE.md` is historical. The scheduled-tick and
  passenger-rule fixes now have live checks; full interacting redstone machines, dynamically
  changing passenger trees, and modpack-specific cross-region behavior still need coverage.

See [development and validation](DEVELOPMENT.md) for commands and the live-test matrix.

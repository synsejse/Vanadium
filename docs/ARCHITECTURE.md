# Architecture

Vanadium parallelizes stages within a world. Worlds advance sequentially through one
server-owned scheduler and worker pool; it is not a server-per-region engine.

## Ownership and stages

| Package | Responsibility |
| --- | --- |
| `tick` | Collection, adaptive planning, stage execution, profiling and diagnostics |
| `chunk` | Loaded-full-chunk publication, synchronous load locks, packet preparation |
| `tracking` | Nearby-player ranges, tracker diffs and navigation invalidation |
| `entity` | Spatial indexing of entity sections |
| `collections` | Fastutil-compatible adapters over JDK/JCTools collections |
| `concurrent` | Striped interaction locks and per-thread world random streams |
| `commands`, `config` | Operator reports, configuration and serial-type rules |
| `mixin` | Java integration with vanilla; bootstrap compatibility stays Java too |

`Vanadium` creates fresh scheduling state on server start and closes diagnostics and the
worker pool after the last wave on shutdown. Chunk caches own their maintenance executors;
load-lock tables own no threads. Integrated-server reopen must create a new lifecycle.

| Stage | Collection and completion boundary |
| --- | --- |
| Scheduled block/fluid ticks | Collect under the vanilla scheduler monitor, dispatch after releasing it |
| Spawning/thunder | Complete before the separate block-ticking chunk pass |
| Random/precipitation chunk ticks | Complete before custom spawners and broadcasts |
| Tracking | Prepare visibility diffs, then complete tracker callbacks |
| Entities | Queue vanilla's complete per-entity body; finish before block-entity bookkeeping |
| Block entities | Queue supported tickers; finish before clearing the ticking flag |

Disabled stages retain serial fallbacks. Structural synchronization and loaded-chunk
indexing remain installed even with `enabled=false`. Projectiles, entities in portals and
configured exact-ID exceptions tick serially. A serial passenger routes its whole vehicle
tree through the serial path.

## Planning and execution

`BatchEnqueue` maps large recorded inputs in disjoint worker slices, then enqueues the
results on the caller in recording order. Workers never mutate stage queues.

`RegionGrid` records tasks per chunk, using floor-correct coordinates and dense 16×16
tiles. Prefix sums guide quadtree splits: the task target is the larger of a share of
work (`tasks / (participants × 4)`) and approximately 25µs of measured region work.
Hotspots split; quiet or cheap areas stay merged. A region is its chunks' bounding box;
tasks within it run serially in enqueue order.

Boxes within `isolation` chunks of each other in Chebyshev distance conflict. Conflict
lists are symmetric and the greedy colouring is proper. This safety distance does not
depend on region shape or worker count. Stable plans are reused for up to 20 waves while
membership, task-count drift and estimated cost remain within their bounds. New active
chunks, changed participants/isolation or expired plans trigger rebuilding.

Both policies implement `RegionWave`:

- `ClaimWave` dynamically claims heavy regions (estimated average ≥100µs). Volatile
  PENDING/TRYING/RUNNING/DONE states and index-ordered claim resolution prevent conflicting
  work from overlapping. Failed scans wait for completion progress instead of rescanning.
- `DataflowWave` handles cheaper work. A region becomes ready after all lower-coloured
  conflicts finish. Atomic dependency counters and ready slots avoid repeated scanning.

Both complete the whole wave before propagating task failures. There are no global
barriers between colours, but every stage has a full completion barrier. Relative order
between conflicting regions may change between ticks.

`WorkerPool` publishes one immutable `WorkBatch`; both helpers and the calling server
thread claim work. One-task waves run inline. Helpers spin briefly before parking; the
caller spins then parks for the tail. Late helpers use a saved task count, never a list
that the caller has cleared. Interruption restores the caller's flag only after all work
finishes. Never hold a monitor needed by workers while joining a wave.

## Collections and publication

JCTools `NonBlockingHashMapLong` owns primitive-key storage and resizing for loaded full
chunks, entity sections, column snapshots and byte-valued simulation/POI levels. Steady
lookups take no shared monitor and do not box position keys. Byte values use the JVM cache.

Fastutil field types require small adapters:

- `ConcurrentLongByteMap` adapts JCTools to `Long2ByteMap`, preserving default-return values
  and fastutil views. Its direct visitor uses primitive-key iteration.
- `ConcurrentLongObjectMap` and `ConcurrentIntObjectMap` adapt JDK `ConcurrentHashMap` and
  retain atomic compute functions. Packed long keys use fastutil's Murmur3 hash encoding;
  views decode original keys. Raw packed positions otherwise collapse into CHM tree bins.
- `ConcurrentLongSet` adapts `ConcurrentSkipListSet` to vanilla's concrete
  `LongLinkedOpenHashSet` fields. Every superclass storage reader is overridden. Iteration
  is ascending, not insertion order.

Spatial indexes remain domain logic rather than general-purpose containers. `AreaMap`
paints nearby-player ranges and updates only their symmetric difference. `EntitySectionIndex`
visits only columns intersecting an entity query, preserving vanilla's packed-key order.
`NavigationIndex` filters invalidation candidates; dirty/custom/long-path cases retain a
conservative fallback. It does not run pathfinding asynchronously.

## World access

- `ParallelChunkCache` serves loaded FULL hits from the concurrent map. `LevelChunk.setLoaded`
  publishes/removes entries; a private publication monitor protects identity-conditional
  removal so an old unload cannot remove a replacement. Reads stay outside that monitor.
- Misses/status requests stay with vanilla/C2ME under per-position reentrant load locks
  (or the cache monitor with parallel loading disabled). FULL publication is rechecked
  after acquiring the load lock. Never publish null or non-FULL results. Minecraft's
  own background workers bounce synchronous loading to the server thread.
- Vanadium workers never wait for chunk work only the server thread can do: it is in the
  same wave, and C2ME makes new holders visible only during its tick. A worker gets a chunk
  only if C2ME has already completed the requested status. Otherwise a task run through
  `WorkerPool.runDeferrable` unwinds with `ServerThreadRequired` and reruns on the server
  thread after the wave; other callers log a rate-limited warning and wait as before.
  Spawning is deferrable: it prechecks the chunk's referenced structure starts, so the
  usual deferral (joins and exploration) happens before any spawn attempt.
- `ChunkLockTable` reserves entries atomically against eviction. Unlock before dropping a
  reservation; maintenance must not evict a held or pending lock.
- Per-holder synchronization protects chunk block/light updates. Dirty notifications queue
  to the server thread; workers never mutate the ordered eager-save set or wait for disk IO.
- Section storage (POI) gets its concurrent map and dirty set when constructed. Callers loading the
  same column wait for its first loader, which reads off the server thread; vanilla let a second
  caller read the column before its sections were stored.
- Saved-data cache hits are concurrent; misses and snapshot creation synchronize separately
  from asynchronous disk writes. Never hold that monitor across `saveAndJoin` or close waits.
- Immutable path-type entries prevent torn key/type reads. POI and simulation levels use
  the byte-map adapter. Per-thread random streams avoid a shared world-random monitor.

Isolation does not protect arbitrary cross-region interactions. Entity/item locks, shared
world monitors and collection contracts are still required. Item pairs lock in ID order;
pickup and hopper paths share those locks. Neighbor propagation retains its world monitor.

## Preparation and networking

Spawn preparation replaces vanilla's entity iterator with scoped per-thread slices,
retaining C2ME filtering and vanilla's environment-aware costs. Workers count independent
slices; the caller reduces counts, player caps and charges in input order. Overrides clear
in `finally`. Spawn attempts remain a separate stage.

Tracking owns reusable per-player diff rows and stable tracker slots through both barriers.
Workers prepare flags without callbacks; one subsequent task per tracker applies changes
in player order. Chunk packet preparation runs between simulation ticks, keeps selected
batch order and vanilla packet contents, and falls back inline for small batches.

C2ME owns generation, holder promotion and ticket scheduling. It is required. Two named
thread-detection mixins are cancelled by the Java bootstrap integration. VMP is incompatible.

Lithium 0.26.3+ is optional. `fabric.mod.json` disables, through `lithium:options`, the Lithium
options that rewrite paths Vanadium owns: chunk access, entity-section collections and retrieval,
the entity tick list, navigation tracking and the profiler; Lithium then turns off the cramming and
projectile-collision options that depend on them. The rest stay enabled. Lithium state that parallel
stages share is made safe:

- POI section storage keeps Lithium's per-column index on a concurrent listening map; writes to one
  column and their index updates hold a column stripe lock, and reads take none.
- Game-event dispatcher storage takes a write lock for its rare updates; reads are optimistic and
  retry under a read lock only if a write overlapped them.
- Tracker interning and chunk-section callback registration (hoppers) are synchronized; both are rare.

These compatibility mixins are `@Pseudo` and inert without Lithium. `MixinTargetsTest` checks their
bindings against the pinned Lithium jar, and live runs include Lithium unless `-Plive.lithium=false`.

## Validation boundaries

Correctness tests cover scheduling, collection semantics and selected transformed-server
paths. [Performance evidence](PERFORMANCE.md) applies only to its recorded workloads.
Real clients, modpacks, interacting redstone/fluid machines, exploration, dimensions and
integrated-server reopen still need manual coverage. Residual vanilla four-entry chunk
cache access on misses/non-FULL requests remains an audit item: per-position load locks
alone do not make its parallel-array reads safe.

Use [development](DEVELOPMENT.md) for checks and [profiling](PROFILING.md) for investigation.

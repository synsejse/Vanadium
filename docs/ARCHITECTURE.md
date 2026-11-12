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
| `concurrent` | Striped interaction locks, per-thread world random streams and random-tick LCG |
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
| Entities | Vanilla's complete per-entity body as one flat wave; finish before block-entity bookkeeping |
| Block entities | Queue supported tickers; finish before clearing the ticking flag |

Entities are the exception to region scheduling: see [Flat entity wave](#flat-entity-wave).

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

## Flat entity wave

Regions cannot help where work is packed together: a base or a mob farm is one clique of conflicting
regions, and its entities would tick serially while the other workers wait. The ENTITY stage
therefore has no regions and no isolation: every entity tick is one task in a single wave, wherever
it is. Other stages keep region scheduling.

What made this safe before was distance; now it is ownership (`Interactions`, `OwnerLocks`):

- A tick owns its vehicle tree: it holds that root entity's lock from start to end. Everything it
  writes to itself needs nothing else.
- Reads of other entities and of blocks take no lock, exactly as between regions. Sorts by distance
  snapshot positions first (`StableDistance`), because entities move while they are being sorted.
- A push, the one thing crowded entities do to each other every tick, goes to the target's inbox and
  never waits; the target applies it at the start of its tick, or the server thread after the wave.
- Any other write to another tree is a foreign write. Mixins at the mutation points open a scope:
  the tick releases its own lock and takes the target's, so the write waits for the target's own
  tick, and for nothing else: writes to different entities do not wait for each other. Releasing
  first keeps two entities that write to each other from deadlocking; the tick is open to foreign
  writes while it is in the scope, at a point where it was calling out, and a write back to itself
  in there takes its own lock again. The generic points are velocity, position, removal, synced data
  and brain memories; damage, effects and mounting are scoped as whole operations.
- Operations that two entities start on each other in the same tick take both locks in id order
  (a pair scope) and recheck their condition inside, so the second sees what the first did: two stacks
  merging, two orbs merging, a mob taking an item, two parents breeding (one birth), villagers
  gossiping.
- Block changes, explosions and vibration delivery from entity ticks are shared writes: they run one
  at a time on a shared lock, then follow the same per-chunk and neighbour-update synchronization as
  region work. Hoppers and beehives serialize the items and bees that enter them.
- Entities whose ticks mostly write to others run serially before the wave, like projectiles: TNT,
  container and TNT minecarts, the dragon, the wither, end crystals, lightning, effect clouds, evoker
  fangs, copper golems and wardens.

A scope nested in another (damage whose side effects reach a third entity) holds one lock while
waiting for the next, so two such writers can wait on each other. A waiter follows the chain of
holders and what each waits for; if it leads back to itself it proceeds without that lock, and counts
it. Locks outside the protocol are invisible to that check, so every wait is also bounded (100 ms).
Either way one write runs unprotected instead of stalling the tick, and is reported. `Interactions`
counts foreign writes by kind (`-Dvanadium.interactionLogWaves=N` logs them). The catalogue of whole
operations is vanilla's; a mod's own pairwise logic is covered only by the generic points.

Overlapping ticks can also fail where serial ticks cannot: a value read after another tick removed
it. Such failures do not repeat, so with `skipFailedEntityTicks` one failed tick is logged and its
remainder skipped; the same entity failing three ticks running is a real bug and stops the server
as vanilla does.

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
visits only columns intersecting an entity query, preserving vanilla's packed-key order. Each
chunk column is one immutable entry (sorted Ys and their sections) replaced on change, so a column
costs one lock-free map read and readers never see half of a write.
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
  The server thread defers deferrable tasks the same way while helpers run: it could load,
  but the chunk-system work (status changes, entity and POI loading, mods' load callbacks)
  would then overlap the workers. `WorkerPool.runAfterParallelWave` queues other such
  server-thread work until the wave's tasks have all finished. Spawning is deferrable: it prechecks the chunk's referenced structure starts, so the
  usual deferral (joins and exploration) happens before any spawn attempt.
- `ChunkLockTable` reserves entries atomically against eviction. Unlock before dropping a
  reservation; maintenance must not evict a held or pending lock.
- Per-holder synchronization protects chunk block/light updates. Dirty notifications queue
  to the server thread; workers never mutate the ordered eager-save set or wait for disk IO.
- Section storage (POI) gets its concurrent map and dirty set when constructed. Callers loading the
  same column wait for its first loader, which reads off the server thread; vanilla let a second
  caller read the column before its sections were stored. POI sections synchronize their methods,
  and their per-type map and sets are concurrent: vanilla's record streams and Lithium's iterators
  are consumed after the section's lock is released, while other regions add POI.
- Saved-data cache hits are concurrent; misses and snapshot creation synchronize separately
  from asynchronous disk writes. Never hold that monitor across `saveAndJoin` or close waits.
- Immutable path-type entries prevent torn key/type reads. POI and simulation levels use
  the byte-map adapter. Per-thread random streams avoid a shared world-random monitor, and
  `Level.randValue`, the random-tick position LCG, is per-thread: `SynchronisePlugin` rewrites
  every access in `Level` and `ServerLevel` after all mixins, covering Lithium's shortcuts too.

Isolation does not protect arbitrary cross-region interactions. Shared world monitors and
collection contracts are still required. Neighbor propagation retains its world monitor.

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

Lithium 0.26.3+ is required; Vanadium compiles against it. `fabric.mod.json` disables, through
`lithium:options`, the Lithium options whose job Vanadium does with thread-safe structures: chunk
access, entity class filtering and per-type lists, entity retrieval, the entity tick list, navigation
tracking and the profiler. It also disables `alloc.chunk_random`, which shares one mutable position per
world between parallel random ticks. The rest stay enabled, and Lithium state that parallel stages
share is made safe:

- Entity class groups (`ClassGroupFilterableList`) come from Vanadium's copy-on-write
  `ClassInstanceMultiMap`; `compat.Lithium` cancels Lithium's single-threaded lists. Lithium's entity
  collision, cramming and projectile-collision shortcuts depend on them and stay on. A section's
  cramming filter is started by its readers; readers of one section serialize, which matters only at
  isolation 1, where two regions can read a boundary section at once.
- POI section storage keeps Lithium's per-column index on a concurrent listening map; writes to one
  column and their index updates hold a column stripe lock, and reads take none.
- Game-event dispatch reads per-chunk listener maps without the chunk's monitor, so those maps are
  concurrent and removal takes the monitor that creation already held. The per-world dispatcher
  storage takes a write lock for its rare updates; reads are optimistic and retry under a read lock
  only if a write overlapped them.
- Chunk load/unload callbacks (`ChunkStatusTracker`) run on the server thread, which can change chunk
  status mid-wave; they are queued until the wave's tasks finish, before the chunk unloads.
- Tracker interning, the world-border listener registry (every block-entity ticker re-registers after
  a border change) and chunk-section callback registration (hoppers) are synchronized; all are rare.

Users may still disable Lithium options in `config/lithium.properties`; injections into methods those
options add are optional (`require = 0`), and section storage falls back to Vanadium's concurrent map.
`MixinTargetsTest` checks bindings against the pinned Lithium jar, and every live run includes Lithium.

## Validation boundaries

Correctness tests cover scheduling, collection semantics and selected transformed-server
paths. [Performance evidence](PERFORMANCE.md) applies only to its recorded workloads.
Real clients, modpacks, interacting redstone/fluid machines, exploration, dimensions and
integrated-server reopen still need manual coverage. Residual vanilla four-entry chunk
cache access on misses/non-FULL requests remains an audit item: per-position load locks
alone do not make its parallel-array reads safe.

Use [development](DEVELOPMENT.md) for checks and [profiling](PROFILING.md) for investigation.

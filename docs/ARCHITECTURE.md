# Architecture and investigation notes

Vanadium moves parts of vanilla server ticking onto a shared worker pool. It is not a
server-per-region architecture: worlds still advance one at a time, and the server
thread gathers work and waits at explicit barriers.

## Scheduling

`Vanadium.onInitialize()` creates one `WorkerPool` and `TickScheduler`. Each stage has
a reusable `CellGrid`. Chunks map to cells using floor division; parity of the cell's
X/Z coordinates chooses one of four colors. A stage runs colors 0 through 3 in order.
Cells of one color may run concurrently; tasks inside a cell run in insertion order.
The grid reuses cells and buckets and evicts cells idle for more than 100 stage begins.

`WorkerPool.runWave()` claims tasks from an indexed list on both pool threads and its
caller. A private `Wave` holds the claim index, fixed task count, remaining count, completion
latch, and first failure. Only the last completed task signals the latch. The scheduler
keeps the list stable until every task finishes; late helpers
only check the saved count, allowing the caller to reuse the list after the barrier.
Other collection types are copied to an array-backed list. An interrupted caller still
waits for all tasks and restores its interrupt flag before returning or propagating a
failure. A one-task wave runs directly on the caller. This is why unconditional tick-path
synchronization matters.

The actual stage timing comes from Minecraft mixin injection points, not the enum alone:

| Work | Main integration points |
| --- | --- |
| Scheduled block/fluid ticks | `ServerWorldMixin.overwriteScheduledTicks` collects then drains each vanilla scheduler |
| Natural spawning and random/weather chunk ticks | `ServerChunkManagerMixin` queues work; `ServerWorldMixin.postChunkTick` drains it |
| Entity tracking | `ServerChunkLoadingManagerMixin` wraps tracking dispatch and its barrier |
| Entities | `ServerWorldMixin` queues; `WorldMixin` drains before block entities |
| Block entities | `WorldMixin` queues supported tick invokers and drains at the end |

Projectiles and entities currently in portals retain serial entity ticking. Stage flags
and `enabled` select fallback paths, but mixins and structural synchronization remain installed.

## Shared state and compatibility

Coloring separates neighboring cells; it does not bound every possible interaction.
Vanadium also replaces collections, adds monitors, uses striped entity locks, and stamps
`ACC_SYNCHRONIZED` on selected vanilla instance methods through `SynchronisePlugin`.
Changing a mixin ordinal, lock scope, or collection view can change correctness even when
the scheduler tests pass.

`ParallelChunkManager` caches weak chunk references by packed position and required status.
Loads use `ChunkLockTable` or a global monitor, depending on configuration. The lock table
reserves entries atomically before acquiring locks, then evicts unreserved entries periodically.
Both load paths recheck and publish through `loadAndCache` while holding their load lock;
null results are not inserted. Keys remain immutable records allocated per lookup, and
the existing ten-second cache clear remains in place.
`ParallelChunkManager` identifies Minecraft Main workers by comparing their owning pool
with `Util.getMainWorkerExecutor()`; chunk requests from those workers return to the server thread.

Item merging retains one global reentrant lock. Its method wrapper releases the lock in
`finally`, including when vanilla code or another injection throws.

`AreaMap` and `NearbyTrackers` index nearby tracking relationships to avoid scanning all
players for every entity. Fresh trackers stay in an identity-keyed linked map holding their
arrival ticks; this preserves FIFO migration after 200 ticks while allowing direct removal.
Area updates paint and erase the non-overlapping strips of the old and new squares, skipping
unchanged coverage. Disjoint teleports still update both complete squares.
Tracking, chunk transitions, and packet flush consolidation need
live multiplayer coverage; unit scheduler tests do not exercise them.

C2ME provides parallel generation/loading and bundles MixinSquared. `compat/C2ME.java`
cancels two named C2ME thread-detection mixins. `fabric.mod.json` requires C2ME and rejects
Lithium and VMP. The automated harness pins the C2ME release that was in this checkout's
development mod directory; it does not include the optional ScalableLux jar found there.

## Follow-up areas for correctness work

These are source-inspection leads, not a completed concurrency audit:

- `ChunkLockTable` adds packed offsets to packed positions. Radius-zero loads are the
  current caller; test coordinate-boundary behavior before using nonzero radii elsewhere.
- Pool and lock-evictor lifecycle should be checked across integrated-server stop/restart.
  The initializer creates the worker pool without registering a shutdown callback.
- `fabric.mod.json` advertises `1.21.x`, while the build and injections target 1.21.1.
  Treat other patch versions as unverified until explicitly tested.

See [development and validation](DEVELOPMENT.md) for commands and the live-test matrix.

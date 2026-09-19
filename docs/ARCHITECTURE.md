# Architecture and investigation notes

Vanadium moves parts of vanilla server ticking onto a shared worker pool. It is not a
server-per-region architecture: worlds still advance one at a time, and the server
thread gathers work and waits at explicit barriers.

## Scheduling

Each server startup creates its own `WorkerPool` and `TickScheduler`. Each stage has
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
| Scheduled block/fluid ticks | `ServerLevelMixin.dispatchScheduledTicks` collects then drains each vanilla scheduler |
| Natural spawning and random/weather chunk ticks | `ServerChunkCacheMixin` drains spawning before random ticks, then random ticks before custom spawning/broadcasts |
| Entity tracking | `ChunkMapMixin` wraps tracking dispatch and its barrier |
| Entities | `ServerLevelMixin` queues; `LevelMixin` drains before block-entity bookkeeping |
| Block entities | `LevelMixin` queues supported tick invokers and drains at the end |

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
from total stage duration and is not a measurement of lock contention. Cell/task counts
are read on the server thread while wave lists are stable. There are no worker-side profile
counters or per-task timers in normal operation. Opt-in profiles time each cell into a
separate array slot, then aggregate samples on the caller after the barrier. Per-world
preparation intervals include collection and inline fallbacks, and world totals overlap
stage timings. Spawning/thunder and random/precipitation work have separate stage entries.
Capture duration is 1–300 seconds, with at most 65,536 stored
samples per series; reaching the tick sample limit also finishes the capture. Stage series
that reach their cap retain totals and label their percentile truncation. Server stop clears
active/previous captures and releases the command source.

`WaveDiagnostics` is an optional daemon watchdog, created for each server lifecycle and
closed at server stop. The scheduler publishes one watched wave with its dimension/stage
context. Caller and workers publish/remove their current cell around execution; detailed
mode also updates a volatile task label. The watchdog reads this progress map and thread
stacks without acquiring world or scheduler monitors. Reports are bounded and rate-limited,
and completed waves are detached in `finally`, including failures. Task completion barriers
and interruption behavior are unchanged. No monitoring thread is started while disabled.

## Shared state and compatibility

Scheduled ticks remain in an ordered, identity-indexed pending queue until a callback claims
them under the vanilla scheduler monitor. Pending queries and area copying retain their
bookkeeping during the wave, and area clearing cancels unclaimed callbacks. Collection,
claiming, and final cleanup hold the monitor; execution and the completion barrier do not.
Block events retain vanilla's ordered deduplication under a separate set monitor. Random
block-position seed transitions use a dedicated short-lived lock, preserving the vanilla
sequence without locking the world during callbacks.

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
the existing ten-second cache clear remains in place. One maintenance executor per chunk
manager handles cache clearing and thirty-second lock eviction. `ChunkLockTable` owns no
threads. Closing the chunk manager finishes vanilla saving, then closes maintenance and
clears cached references, including when vanilla close throws.
`ParallelChunkManager` identifies Minecraft Main workers by comparing their owning pool
with `Util.backgroundExecutor().service()`; chunk requests from those workers return to the server thread.

Server shutdown closes the watchdog and worker pool after the final wave and clears
the scheduler, profiler, and benchmark command references. Starting another integrated
server creates fresh scheduling state and resolves the configured worker count again.

In 26.2, `TicketStorage` owns the tickets formerly held by the simulation tracker and is
synchronized through `SyncAllMixin`. `SavedDataStorageMixin` protects cache operations and
save snapshot creation, while asynchronous disk writes and save completion waits remain outside
that monitor. `ServerChunkCache` also has a concurrent set for pending chunk broadcasts.

`ChunkHolder` keeps vanilla's section update sets. A per-holder monitor protects block/light
update registration and broadcasting, including set creation, clearing, and pending flags.
Replacing only an array entry is insufficient in 26.2: `blockChanged` adds the first position
through a local set reference, which must remain the same object that broadcasting reads.

Chunk dirty notifications enter a concurrent queue. The server thread drains them into
`ChunkMap.chunksToEagerlySave` at chunk-map maintenance and eager-save entry points. This
preserves vanilla's ordered, deduplicated save set and C2ME's idle autosave iteration;
workers never mutate that set or wait for disk work while marking a chunk dirty.

Item merging retains one global reentrant lock. Its method wrapper releases the lock in
`finally`, including when vanilla code or another injection throws.

`AreaMap` and `NearbyTrackers` index nearby tracking relationships to avoid scanning all
players for every entity. Fresh trackers stay in an identity-keyed linked map holding their
arrival ticks; this preserves FIFO migration after 200 ticks while allowing direct removal.
Area updates paint and erase the non-overlapping strips of the old and new squares, skipping
unchanged coverage. Disjoint teleports still update both complete squares.
Tracking, chunk transitions, and packet flush consolidation need
live multiplayer coverage; unit scheduler tests do not exercise them.

Tracking preparation partitions visibility diffs by player once at least two players and
1,024 candidate/known comparisons are present. Smaller workloads run inline. The nearby
index stays stable across the preparation barrier; each job owns one player's known set
and only records actions. The caller merges these actions in player order, deduplicates
entry ticks, and enqueues normal per-tracker cell work. Workers in this preparation phase
must not invoke tracking callbacks or acquire the nearby-index monitor held by the caller.

`NavigationIndex` conservatively indexes vanilla navigation invalidation ranges. Block
changes still call vanilla's exact predicate on the candidates. Membership changes, movement,
path replacement, recomputation and navigation ticks mark mobs dirty; dirty mobs remain
candidates for every block change until the next world-tick boundary refresh. Refresh reads
navigation state outside the index monitor and retains the fallback if concurrent changes
occur. Custom predicate overrides and paths longer than 256 remaining nodes use the full
fallback. Mods mutating path internals outside normal navigation entry points require explicit
integration. This is a candidate filter, not asynchronous pathfinding or delayed recomputation.

C2ME provides parallel generation/loading and bundles MixinSquared. `compat/C2ME.java`
cancels two named C2ME thread-detection mixins. `fabric.mod.json` requires C2ME and rejects
Lithium and VMP. The automated harness pins the C2ME release that was in this checkout's
development mod directory; it does not include the optional ScalableLux jar found there.

## Follow-up areas for correctness work

These are source-inspection leads, not a completed concurrency audit:

- `ChunkLockTable` adds packed offsets to packed positions. Radius-zero loads are the
  current caller; test coordinate-boundary behavior before using nonzero radii elsewhere.
- Full integrated-server join/leave cycles still need interactive coverage. Lifecycle
  callback reinitialization and dedicated-server resource shutdown have automated coverage.
- The 1.21.1 source audit in `TICK_COVERAGE.md` is historical. The scheduled-tick and
  passenger-rule fixes now have live checks; full interacting redstone machines, dynamically
  changing passenger trees, and modpack-specific cross-cell behavior still need coverage.

See [development and validation](DEVELOPMENT.md) for commands and the live-test matrix.

# Server tick coverage and follow-up review

Source review on 2026-09-13, against Vanadium `9ef37e0`, Minecraft 1.21.1,
and Yarn 1.21.1+build.3. The matching generated Minecraft source jar already
existed in `.gradle/loom-cache/minecraftMaven/`; its ZIP integrity was checked,
so `genSources` did not need to run. Selected sources were extracted into
ignored `.vanadium/tick-audit/sources/` for inspection.

This maps the vanilla source to Vanadium's injections. It is not a runtime
concurrency certification or a benchmark. Other mods can change these paths.
The pinned C2ME jar was inspected for relevant mixin/symbol references, but
this was not a complete audit of the transformed runtime classes.

## What a tick does

`MinecraftServer.tick` advances the tick manager, calls `tickWorlds`, handles
periodic server metadata and autosaving, and updates timing statistics.
`tickWorlds` runs command functions, iterates worlds sequentially, ticks
connections, updates player latency, runs GUI/development callbacks, sends
chunk batches, and enables connection flushing. The dedicated-server override
then executes queued console commands.

For an ordinary, unfrozen world tick, the main simulation sequence is:

1. World border, weather state, sleep handling, darkness, time and scheduled functions.
2. Scheduled block ticks, then scheduled fluid ticks.
3. Raid management.
4. Chunk manager: tickets, chunk state transitions, chunk selection, spawn counting,
   natural spawning/random ticks, special spawners, chunk broadcasts, entity tracking,
   POI maintenance and unload/save work.
5. Synced block events, including piston block events.
6. Dragon-fight management, entity eligibility/despawn checks, entity ticks and passengers.
7. Block-entity ticking.
8. Entity load/unload management.

Tick freezing, debug worlds and empty-world idling gate parts of this sequence.
The outer server loop also pumps queued server/chunk tasks between calls to
`MinecraftServer.tick`; that work is outside the current tick profile's event window.

## Current coverage

“Parallel” means independent cells of the same color can run concurrently.
Each cell is serial, colors have completion barriers, and worlds and stages
do not overlap. The server thread participates; a one-cell wave runs inline.

| Work | Current execution | Remaining serial work or limitation |
| --- | --- | --- |
| Scheduled block/fluid callbacks | `SCHEDULED_TICK`, separate drains for blocks and fluids | Due-tick selection and scheduling bookkeeping stay serial; cross-cell priority/interleaving differs from vanilla |
| Natural per-chunk spawning | `CHUNK` | `SpawnHelper.setupSpawn` scans/counts entities serially; shared `SpawnHelper.Info` methods are synchronized |
| Random block/fluid ticks | `CHUNK` | Chunk selection, shuffle, eligibility and inhabited-time updates stay serial |
| Lightning and ice/snow work inside `tickChunk` | `CHUNK` | Global weather state remains serial |
| Ordinary entity ticks | `ENTITY` | Collection, despawn/eligibility checks, vehicle checks and configured fallback tickers remain serial |
| Mob AI, brains, goals and navigation/pathfinding invoked by an entity tick | Inside that entity's task | No separate asynchronous pathfinding stage; calls outside these tasks follow their caller |
| Passenger ticks | Inside their vehicle's task, recursively | Not independently scheduled; serial-rule caveat below |
| Supported wrapped/direct block-entity tickers | `BLOCK_ENTITY` | List maintenance, eligibility checks, unknown invoker implementations and configured serial types stay serial |
| Player ticking | Partial: world-side `ServerPlayerEntity.tick` can enter `ENTITY` | `ServerPlayNetworkHandler.tick` calls `playerTick` serially later, including base player simulation, inventory/map sync, health and score updates |
| Per-tracker entry/status/stop-tracking work | `TRACKING` | Area-map maintenance, movement detection, player diffs and chunk-watch packet setup stay serial |
| Special spawners | Serial | The `SpecialSpawner` loop, distinct from ordinary natural spawning |
| Raids and dragon-fight management | Serial | Individual participating mobs can still tick in `ENTITY` |
| Synced block events | Serial drain | A concurrent event queue does not make its consumer parallel |
| Neighbor updates/redstone propagation | Runs synchronously on the triggering thread | May originate in a worker task, but has no separate parallel stage; neighbor-updater synchronization can serialize work |
| Border, global weather, time, sleep, functions and commands | Serial | Global state and observable ordering |
| Connection ticks, player latency, chunk batch dispatch | Serial orchestration | Netty already handles transport asynchronously; Vanadium batches worker sends and avoids blocking on disconnect |
| Tickets, POI/entity maintenance, unload/save orchestration | Serial orchestration | Some backing jobs already use vanilla/C2ME asynchronous facilities |
| Chunk generation/loading and related background work | C2ME/vanilla executors, with Vanadium lookup caching/load locks | Not additional Vanadium tick stages; background work still competes for CPU time |
| Autosave, server bookkeeping and global mod callbacks | No general Vanadium parallel dispatch | A callback invoked inside a redirected ticker inherits that ticker's execution thread |

Main integration sources: `ServerWorldMixin`, `ServerChunkManagerMixin`,
`ServerChunkLoadingManagerMixin`, `WorldMixin`, `NearbyTrackers`, and
`TickScheduler`. Networking changes are in `ClientConnectionMixin` and
`ServerCommonNetworkHandlerMixin`.

## Changes to prioritize before expanding parallelism

These are source-backed semantic differences or exposed shared state. Their
gameplay consequences still need focused live regression scenarios.

### 1. Preserve scheduled-tick bookkeeping through callback execution

`WorldTickScheduler.tick` normally collects due ticks, invokes callbacks, then
clears its execution bookkeeping. Vanadium's replacement callback only enqueues;
the actual callbacks run after that entire method, including `clear`, returns.
Consequently, remaining due ticks are no longer visible through `isTicking`
during callbacks. Comparators, redstone gates and redstone torches use this query
to decide whether to schedule updates. Tick-area copy/clear operations also
deserve review against the deferred execution model.

Preserve the execution bookkeeping for the duration of the wave while keeping
the worker barrier outside the scheduler monitor. Merely moving the barrier
inside the synchronized method would reintroduce the follow-up scheduling deadlock.
Test interacting gates/torches/comparators, fluid propagation and tick-area operations.

### 2. Restore block-entity lifecycle boundaries and safe registration

The ENTITY barrier currently runs at the block-entity list's `iterator()` call,
after vanilla has marked block-entity iteration active and merged pending tickers.
A block entity registered by an entity task therefore misses that merge, unlike
an entity tick completed before `tickBlockEntities` starts.

The BLOCK_ENTITY barrier runs at `Profiler.pop`, after vanilla sets
`iteratingTickingBlockEntities=false`. A worker that registers another ticker
therefore writes to the primary ticker list. Both primary and pending lists are
plain `ArrayList`s in vanilla, and Vanadium does not replace or serialize these
registration paths. Concurrent registration needs protection regardless of which
list receives the additions.

Finish entities before entering block-entity bookkeeping; keep the appropriate
iteration state until block-entity work completes; stage registrations safely
and apply them on the server thread. Test entity-created block entities,
block-entity-created tickers, removal and unload while tasks are queued.

### 3. Finish CHUNK work before its downstream consumers

Vanadium drains CHUNK in `ServerWorld.tick` after `ServerChunkManager.tick`
returns. By then, special spawners, chunk broadcasts, TRACKING, and the chunk
manager's POI/unload/save phase have already run. Vanilla executes natural
spawning and random ticks before those operations. For example, an update
created by deferred random ticking misses the earlier chunk broadcast pass.

Move the drain to a semantic boundary inside the chunk tick path, before the
special-spawner/broadcast work, and adjust tracking integration accordingly.
Also test mixed flags: with parallel spawning enabled and parallel chunk ticks
disabled, spawning is queued while the corresponding random tick runs inline,
reversing their normal per-chunk order. Prefer explicit method/field boundaries
over profiler-call ordinals when restructuring these injections.

### 4. Make serial entity rules cover passenger trees

Only root entities pass through the redirected dispatch. Vanilla's
`ServerWorld.tickEntity` recursively invokes `tickPassenger`, which calls
`tickRiding` without consulting Vanadium's serial rules. A listed entity riding
an unlisted vehicle can therefore still execute on a worker.

Treat the vehicle/passenger tree as the scheduling unit and route the whole tree
serially when a member requires it. Preserve vehicle-before-passenger order;
test nested passengers, listed riders, projectiles and portal transitions.

### 5. Review shared state beyond collection safety

`World.getRandomPosInChunk` mutates the separate plain-int `lcgBlockSeed`, which
parallel chunk ticks share. Synchronizing `CheckedRandom` does not protect that
field. Define a safe random-position strategy before trying to remove random
locks for performance; per-cell streams would also change random sequences.

`ServerWorldMixin` replaces the insertion-ordered set used for synced block
events with a concurrent deque. The deque admits duplicate events that the set
would reject. Restore deduplication with defined order and test repeated piston
events before considering parallel event execution.

`SpawnHelper.Info` synchronization protects individual methods, not the entire
cap-check/spawn/accounting sequence. Check resulting cap/density behavior under
concurrent spawning rather than assuming method locks preserve vanilla outcomes.

## Performance work after those fixes

1. **Measure serial work explicitly.** Add collection and fallback timings, plus
   per-world attribution. Distinguish natural spawning from random chunk ticks.
   Current stage numbers measure queued execution only; caller tail wait is not
   a lock-contention measurement. Use a whole-process recording for task-pump,
   C2ME, allocation and monitor contention evidence.
2. **Reduce work before creating more threads.** Profile chunk-list construction,
   spawn counting and tracking diffs. Consider scratch reuse or incremental
   indexes only where lifecycle semantics and measurements justify them.
3. **Reduce dispatch overhead if measured.** Consider one reusable job per chunk
   or tracker and a serial path for small waves. A single crowded cell still has
   a serial critical path; more helpers cannot split it safely by themselves.
4. **Tune the CPU budget with C2ME.** `workers` counts background helpers; the
   caller can add one participant. On a host exposing 72 logical processors,
   auto mode can use 72 helpers plus the caller in a sufficiently large wave.
   Compare explicit counts such as 8, 16, 24 and 32 before treating 72 as useful.
   Keep cell size fixed during the worker-count sweep because auto cell size
   changes with worker count, then sweep cell size separately.
5. **Test socket locality.** Compare one-socket and both-socket CPU/memory
   placement on the actual dual-Xeon host, recording topology, affinity, Java,
   C2ME configuration, heap, modpack and workload. This is an experiment, not a
   prediction that either placement wins. Compare tick percentiles as well as
   throughput, with steady-state and chunk-generation workloads separately.

I would retain serial world orchestration, commands, block-event draining and
save ownership for now. Independent asynchronous pathfinding, world-per-thread
ticking and parallel player connection ticks would require substantially larger
ownership/ordering designs. They are not small uncovered-loop optimizations.

Hardware references: Intel lists 18 cores/36 threads per E5-2699 v3 in its
[processor comparison](https://www.intel.com/content/www/us/en/products/compare.html?productIds=%2F81909%2C81713%2C81059%2C81060%2C81061).
The kernel documentation explains [NUMA topology](https://docs.kernel.org/mm/numa.html)
and [memory placement policies](https://www.kernel.org/doc/html/v4.19/admin-guide/mm/numa_memory_policy.html).
The proposed sweeps are engineering experiments inferred from that topology and
the current pool implementation, not published Minecraft benchmark results.

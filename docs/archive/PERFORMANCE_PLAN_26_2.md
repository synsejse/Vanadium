> Historical evidence from the 26.2 development cycle. Commands and source paths below
> describe that revision; current workflows are in [DEVELOPMENT.md](../DEVELOPMENT.md).

# Minecraft 26.2 performance work

Target: a modpack server on two Xeon E5-2699 v3 CPUs. Prioritize measured reductions
in server-thread work while retaining readable code and explicit ownership rules.
[Feature measurements](BENCHMARKS_26_2.md) replace the earlier speculative speedup
expectations. [Development limitations](DEVELOPMENT.md#current-limitations) define
what still needs validation. The original source audit is retained in Git history.

## Completed implementation

| Commit | Change |
|---|---|
| `d453440` | Scheduled-tick pending state/cancellation; recursive passenger serial rules; ordered block-event deduplication; random-position seed synchronization |
| `305bfb8` | Per-world preparation/execution and cell timings; separate spawn/random-tick stages |
| `7e9a650` | Conservative navigation invalidation index |
| `6256d0f` | Per-player tracking preparation with ordered caller-side merge |
| `a1bdbc8` | Batched spawn-state counting and ordered reduction |
| `d8bbabf` | Per-item interaction locks; removal of neighbor queue copy-on-write storage |
| `bc13da9` | Bounded parallel chunk-section packet preparation |
| `03c404e` | Linear navigation cleanup, cheaper dense candidates and deferred refresh for rare edits |
| `9a04d22` | Reusable player-owned tracking rows and one callback task per tracker |

Spawn preparation now skips snapshots below 1,024 entities when the input exposes
its size, and sizes larger copies directly. The three measured runtime regressions
have been addressed; repeated A/B results and remaining costs are in the report.

Each implementation batch passed a build and live C2ME checks. Correctness fixes
stay enabled during performance comparisons. The benchmark report distinguishes
actual config toggles from test-only controls and narrower contention probes.

Xeon VM profiling (see the [benchmark report](BENCHMARKS_26_2.md#dual-xeon-e5-2699-v3-development-vm-2026-11-08))
removed the measured tick-path monitors, replaced pool dispatch and color barriers, and batched
serial collection: 4,096 AI zombies went from 27.5–32 to 72–76 uncapped TPS at 24–36 workers.

## Next priorities

1. **Reduce remaining preparation allocation.** Large spawn snapshots and chunk packet
   buffers still add caller work. Preserve snapshot consistency, lifetime bounds
   and ordered reductions while reducing those costs.
2. **Expose the host topology to the VM** (threads per core, NUMA cells, vCPU pinning) or give
   it only physical cores; beyond 36 busy vCPUs total throughput currently drops.
3. **Profile the intended pack on the Xeons.** Use steady-state dense-base, dispersed
   players, mob farms, redstone and exploration workloads. Record TPS, tick p95/p99,
   main-thread CPU, allocation and waits. Compare no Vanadium, disabled Vanadium
   and enabled Vanadium separately; disabled mode retains structural mixins.
4. **Tune worker and cell counts together with C2ME's CPU budget.** Start with a
   fixed cell size and compare 8/16/24/32/48 helpers, then vary cell size. Include
   one-socket and both-socket CPU/memory placement. No optimal count or NUMA policy
   has been established on the desktop benchmark machine.
5. **Replace broad synchronization only with a state-ownership design.** Neighbor
   chains still hold a world monitor; shared random and spawn decisions also need
   explicit semantics. Region queues must preserve ordering, recursion limits and
   cross-region effects. Thread-local queues alone do not solve those interactions.

Parallel players/worlds require a larger design for packet order, inventories,
teleports, cross-dimension transfers and shared server state. More world threads
would not accelerate a single overloaded cell. Keep generation/loading/lighting/IO
with C2ME; Lithium and VMP remain incompatible until deliberately integrated.

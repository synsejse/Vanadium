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

Each implementation batch passed a build and live C2ME checks. Correctness fixes
stay enabled during performance comparisons. The benchmark report distinguishes
actual config toggles from test-only controls and narrower contention probes.

## Next priorities

1. **Address measured regressions first.** Navigation cleanup, candidate copying
   and rare-edit refresh overhead now have fixes validated by repeated A/B runs. Tracking
   preparation can spend its savings on records and caller-side merging. Measure
   representative dirty rates/player populations before choosing a fallback policy
   or changing thresholds. Do not assume more helpers fixes extra serial work.
2. **Reduce preparation allocation.** Small spawn-count workloads still copy entity
   membership; chunk packets create additional buffers and copy them again on the
   caller. Preserve snapshot consistency, lifetime bounds and ordered reductions.
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

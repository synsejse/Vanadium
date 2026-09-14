# Minecraft 26.2: disabled versus activated (2026-09-14)

This benchmarks the uncommitted `port/minecraft-26.2` working tree based on
`9ef37e0`. “Off” means Vanadium's master `enabled=false`; “On” means
`enabled=true` with every optimization flag enabled. Both include Vanadium's
structural mixins, synchronized methods, and C2ME. This is not a comparison
against an installation without Vanadium, nor an isolated scheduler benchmark.

## Results

Arithmetic means of per-run measurements; lower tick time and higher uncapped
TPS are better. Idle and concentrated workloads have two runs per mode;
the spread workload has three per mode across two server JVMs.

| Workload | Off mean tick ms | On mean tick ms | Off uncapped TPS | On uncapped TPS | TPS ratio |
| --- | ---: | ---: | ---: | ---: | ---: |
| Idle, 144 forced chunks | 0.0281 | 0.0339 | 34,953.4 | 28,987.6 | 0.83× |
| 256 zombies, one cell | 1.5296 | 1.3892 | 653.5 | 720.0 | 1.10× |
| 1,024 zombies, 16 cells | 6.2931 | 2.3636 | 159.2 | 422.9 | 2.66× |

The spread workload demonstrates useful parallel scaling on this local CPU.
The concentrated workload stays within one entity cell, so its improvement
cannot be attributed to parallel entity ticking alone: the master switch also
changes the chunk-cache and tracking paths. Idle ticking has a small absolute
overhead when enabled. Uncapped TPS measures available throughput; a normal
server still targets 20 TPS.

Each pair below is Off / On. CPU percentages use 100% for one fully occupied
logical CPU and include all threads in the Minecraft JVM. Percentiles are
means of the individual runs' percentiles, not pooled percentiles.

| Workload | p95 tick ms | p99 tick ms | JVM CPU | CPU ms per tick |
| --- | ---: | ---: | ---: | ---: |
| Idle, 144 forced chunks | 0.036 / 0.044 | 0.044 / 0.053 | 100.2% / 187.9% | 0.029 / 0.065 |
| 256 zombies, one cell | 2.277 / 1.903 | 2.653 / 2.334 | 100.3% / 102.8% | 1.534 / 1.429 |
| 1,024 zombies, 16 cells | 8.078 / 3.207 | 9.277 / 4.225 | 100.8% / 278.2% | 6.347 / 6.581 |

| Workload | Mode | Individual uncapped TPS results |
| --- | --- | --- |
| Idle, 144 forced chunks | Off | 33797.6, 36109.2 |
| Idle, 144 forced chunks | On | 28598.4, 29376.7 |
| 256 zombies, one cell | Off | 647.3, 659.7 |
| 256 zombies, one cell | On | 740.9, 699.0 |
| 1,024 zombies, 16 cells | Off | 154.0, 152.8, 170.9 |
| 1,024 zombies, 16 cells | On | 408.3, 435.7, 424.7 |

## Method and configuration

- Local AMD Ryzen 5 9600X: 6 physical cores, 12 logical CPUs, one socket.
  This is not the intended dual Xeon E5-2699 v3 deployment machine.
- Nix-pinned OpenJDK 25.0.4.1; fixed `-Xms2G -Xmx2G`; Loom development server.
  Minecraft 26.2, Vanadium 2.0.0+26.2, Fabric Loader 0.19.3,
  Fabric API 0.160.0+26.2, Cloth Config 26.2.155,
  C2ME 0.4.2-alpha.0.52. The exact C2ME hash remains in `scripts/runtime-mods.json`.
- 12 configured worker threads, with caller participation; cell size explicitly
  fixed at 3 chunks (also the automatic choice for this machine).
  Diagnostics and Vanadium's stage profiler were off during measured runs.
- Fresh loopback-only flat worlds, seed 12345, 144 force-loaded chunks,
  view/simulation distance 4, no players. Time fixed at midnight, weather fixed
  clear, natural mob spawning and autosave disabled. Empty-server pausing disabled.
- Stone floors at Y=100 prebuilt before the first sample. Mobs are persistent,
  invulnerable adult zombies with AI enabled, movement speed zero, and deterministic
  per-entity random seeds. Positions are spaced two blocks apart. Each sample
  recreates its mob population; every created mob was still present at completion.
  The concentrated case uses 256 zombies in one 3×3-chunk cell. The spread case
  uses 64 zombies per cell in a 4×4 cell grid, allowing four entity cells to run
  concurrently in each color wave. It does not saturate all 12 workers.
- Each run has 10 seconds of uncapped warm-up followed by 20 seconds of measurement.
  First JVM: Off/On/On/Off for idle, then concentrated, then spread workloads.
  Second fresh JVM/world: On/Off for the spread workload.
- Vanilla tick sprint removes the 20 TPS cap. A temporary Fabric fixture samples
  wall time between START_SERVER_TICK and END_SERVER_TICK and JVM process CPU time.
  Uncapped TPS includes time between those tick callbacks; tick-work times exclude it.
  Fixture setup, sorting/reporting, and the separate stage profile are outside samples.

## Limits and validation

Background desktop/browser applications were active. A three-second process sample
recorded substantial non-server activity; no unrelated processes were stopped.
The repeated results show the observed range, not confidence intervals or an
isolated-machine performance guarantee. Warm-up is time-based, and mobs advance
through different numbers of AI ticks in faster runs despite fixed spatial layout.

These are synthetic idle/entity workloads. They do not measure player packet load,
real pathfinding under movement/combat, modded block entities, redstone/fluid-heavy
machines, natural spawning near players, chunk generation, disk saving, or dual-socket
NUMA scaling. They do not establish modpack correctness or deployment safety.

All 12 initial measurement windows completed and wrote their results. An unrelated
post-measurement harness assertion then expected 16 newly forced chunks, although
four overlapped the benchmark region and only 12 were new. The fresh-JVM repeat
completed both measurements and its separate stage profile, then encountered another
setup overlap: the harness tried to place a stone block already placed by the fixture.
Both temporary-runner checks were corrected. A final disposable run with 1,024 active
zombies passed explicit save and clean shutdown (`validation.log`, run
`.vanadium/runs/compare-wh4m71tr/`). These were harness assertions after measurement,
not Minecraft tick failures. No throughput samples from this final validation run
are included in the tables.

The separate stage profile recorded 41 ticks and 41,984 entity tasks: exactly
1,024 entity tasks per tick, with 64 tasks per cell. Block-entity and scheduled-tick
task counts were zero, confirming that this does not exercise those workloads.
`git diff --check` passed. All benchmark server and Gradle JVMs were stopped.

## Retained evidence and reproduction

Temporary fixture code and raw artifacts are ignored under `.vanadium/bench-26.2/`:
`CompareBench.java`, `comparison.jar`, `run.py`, `repeat.py`, `heap.gradle`,
`metadata.json`, `background-load.json`, `results.jsonl`, `repeat-results.jsonl`,
`all-results.jsonl`, `spread-stage-profile.txt`, `run.log`, `repeat.log`, and
`validation.log`.
The two run-directory paths are in `server-run.txt` and `repeat-server-run.txt`.

On this checkout, run `nix develop -c python3 .vanadium/bench-26.2/run.py` for
another complete comparison or use `repeat.py` for the fresh-JVM spread pair.
These commands use the already compiled temporary fixture and development classpath;
recompile the fixture against the matching runtime classpath after changing versions.
Generated worlds and artifacts remain outside version control. Production code was
not changed for this benchmark.

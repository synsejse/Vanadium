# Adaptive regions: Minecraft 26.3 A/B

Measured on 2026-11-09. Fixed 2×2 cells on `dev` (`f95121b`) versus the adaptive-grid
working tree based on that commit, with split factor four, cached plans and measured
claiming/dataflow selection. No other runtime optimization was varied. Later changes
add deterministic regression tests and clarify documentation/report wording.

## Results

| World | Fixed grid, TPS | Adaptive regions, TPS | Change |
| --- | ---: | ---: | ---: |
| Uniform crowd | 120.43 | 120.45 | +0.01% (effectively unchanged) |
| Clustered farms | 82.19 | 91.52 | +11.35% |

Means of **all four** 20-second samples per arm, two consecutive samples per JVM,
two fresh JVMs per arm. No outliers were removed. [All 16 samples](adaptive-grid-26.3.csv)
include tick counts and simultaneous profile p50/p95 tick work.

| World | Arm | First JVM: TPS samples | Second JVM: TPS samples |
| --- | --- | --- | --- |
| Uniform | Fixed | 122.00, 123.64 | 119.90, 116.20 |
| Uniform | Adaptive | 122.81, 113.70 | 123.15, 122.13 |
| Clustered | Fixed | 82.64, 82.94 | 82.03, 81.14 |
| Clustered | Adaptive | 92.27, 92.42 | 91.21, 90.18 |

Per world, fresh JVM order was **fixed, adaptive, adaptive, fixed**. Uniform ran first,
then clustered; measurements never overlapped compilation or another server benchmark.
The low uniform adaptive sample (113.70) remains included, as do the low closing controls.
Two JVMs per arm do not establish statistical equivalence or a confidence interval.
Uncapped TPS measures processing capacity; gameplay normally remains at 20 TPS.

## Environment and workload

- Dual Intel Xeon E5-2699 v3 host, KVM guest, 72 vCPUs exposed as two sockets × 18 cores ×
  two threads, two NUMA nodes, NixOS/Linux. No explicit affinity. 32 Vanadium helpers plus
  the participating server thread, default 2ms worker idle spin, isolation two on adaptive;
  fixed-grid control explicitly uses cell size two. Other stage flags remain at defaults.
- OpenJDK 25.0.4.1, `-Xms4g -Xmx4g -XX:+UseG1GC`. Minecraft 26.3, Loader 0.20.1,
  Fabric API 0.165.2+26.3, Fabric Language Kotlin 1.14.2+kotlin.2.4.21,
  Cloth Config 26.3.159, C2ME 0.4.2-alpha.0.116, C2ME global parallelism four.
- Fresh seed-12345 flat worlds; 32×32 force-loaded chunks (chunk coordinates −16..15),
  view/simulation distance four, midnight, natural spawning/time/weather cycles off,
  no players, persistent invulnerable zombies with AI at Y −60.
- Uniform: four zombies in every chunk (4,096 total). Clustered: one per chunk plus
  24 extra in each chunk covered by twelve seeded 3×3 hotspot placements; overlaps are
  deduplicated. This is a deliberately uneven workload, not a same-population comparison
  against the uniform world. Both arms use the same placement recipe within each workload.
- Load chunks, wait ten seconds, summon the crowd, then settle for 15 seconds.
  Each sample runs `/vanadium profile 20` followed immediately by `/vanadium benchmark 20`.
  Both arms pay profiling overhead, including region/cell timing; these are not unprofiled
  production throughput measurements. Profiles overlap tick sprints; their intervals are
  not perfectly identical. Region/cell profile labels differ across implementations.

The run used a temporary external launcher from the investigation, not a supported
repository benchmark task. It is not added back to the project. Full result JSONs and
the launcher console log are preserved locally in `.vanadium/adaptive-evidence/ab-k4/`
and `.vanadium/adaptive-evidence/ab-k4.log`; server logs/worlds remain in the corresponding
isolated `.vanadium/runs/` directories (the control checkout has its own run directory).
The checked-in CSV preserves the measured values, not generated worlds or recordings.
These figures have not been independently reproduced through a checked-in whole-server harness.

The temporary launcher omitted explicit flat-generator settings, so both arms logged
`No key layers in MapLike[{}]` at world creation. In 26.3, `DedicatedServerProperties`
retains the flat preset when that optional settings decode fails; it does not abort the
server or replace it with a normal world. This startup error is a limitation of the
temporary launcher. The supported `runIntegration` fixture supplies explicit layers.

## Choice and limits

The earlier split factor of 16 averaged 117.6 versus 122.2 TPS on uniform crowds (−3.8%)
and 92.3 versus 82.1 on clustered farms (+12.5%). It split the uniform crowd more finely
without shortening its critical path. Factor four retains locality on uniform workloads
while still splitting hotspots. The final table above is a separate complete A/B, not
selected best results from both experiments.

Isolation is a different spatial rule from fixed cells: fixed neighboring-cell exclusion
also serializes some chunk pairs more than two chunks apart. This experiment measures the
whole scheduler change, not the region shape alone. Cross-region locks and serial fallbacks
remain necessary. No result establishes correctness for arbitrary mod interactions.

No vanilla/no-Vanadium arm, real clients, exploration, dimension travel, redstone/fluid
contraptions or modpack workload was measured. No general scaling curve or improved tail
latency is claimed. Real-world coverage remains in [development](../DEVELOPMENT.md).

## Final validation

- Forced tick-package test rerun, then full `./gradlew build`: **141 tests**, no failures
  or errors; access widener and release packaging checks passed.
- `./gradlew runIntegration -Plive.jfr=true`: passed live checks, status command,
  tick/save/shutdown. Evidence: `.vanadium/runs/integration-1c048c0e-eab4-48a0-b80e-8743c41055a9/`.
- Parsed the resulting JFR to EOF: 214 stage-wave events. Monitor/park capture uses
  100µs thresholds. Largest monitor stacks are resource/registry loading through ZipFS;
  this startup-heavy smoke recording is not a steady-state contention comparison.
- Direct deterministic gates exercise independent-region progress and failure release
  for both claiming and dataflow waves. Planner conflict/colour checks include isolation
  1, 2, 4, 8 and 16 across negative coordinates and tile boundaries.
- `git diff --check`: passed. No new Python or shell harnesses are tracked.

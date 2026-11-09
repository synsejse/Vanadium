# Performance evidence

The README figure ([light](assets/performance-light.svg), [dark](assets/performance-dark.svg))
uses **Minecraft 26.3 measurements from 2026-11-09**. It separates the worker-count
investigation on the fixed grid from the later adaptive-region A/B. The curve is **not**
a worker sweep of the adaptive scheduler.

[performance.csv](assets/performance.csv) contains the exact plotted means. Both SVGs use
linear axes starting at zero, straight segments between measured points, and all samples.
No interpolation to unmeasured worker counts, smoothing or confidence interval is claimed.

## Worker scaling: fixed grid

| Active helpers | TPS samples | Mean TPS |
| ---: | --- | ---: |
| 2 | 28.72, 29.93 | 29.32 |
| 4 | 43.20, 43.91 | 43.56 |
| 8 | 66.37, 60.25 | 63.31 |
| 16 | 93.25, 93.59 | 93.42 |
| 24 | 113.44, 110.98 | 112.21 |
| 32 | 116.97, 121.18 | 119.07 |
| 36 | 112.09, 116.03 | 114.06 |
| 48 | 98.84, 100.74 | 99.79 |
| 64 | 85.15, 86.63 | 85.89 |

Source: [all 18 timing samples](benchmarks/worker-scaling-26.3.csv). One fresh JVM per
worker count, two consecutive 20-second profiled tick sprints per JVM. The server thread
participates in addition to the helpers. This is the pre-adaptive fixed 2×2 grid on the
`f95121b` development baseline; all stage flags remain at defaults.

The launcher called the lowest point `workers=1`, but Vanadium clamps positive worker
counts to at least two. The server log confirms **two active helpers**; the sample CSV
preserves both requested and active counts. An initial startup-timeout attempt supplied
no measurements and is excluded; the completed retry supplies this point. It ran after
the ascending 4–64 sweep, not as a randomized control. The other counts ran in ascending
order. Two samples in one JVM are not independent replication across JVMs.

Throughput peaks around 32 helpers and declines by about 28% at 64 in this fixture.
The curve establishes that behavior, not a cause by itself. Separate synthetic hardware
probes scaled much better on compute and random-access loops; streaming bandwidth scaled
poorly. A 16-helper affinity comparison measured 97.3/97.3 TPS on one socket versus
95.6/97.0 across sockets. Those limited diagnostics did not establish clock loss or NUMA
placement as the explanation. Scheduler granularity, locality and workload dependencies
still matter; neither these probes nor this chart isolate their individual contributions.

## Adaptive regions: separate comparison

The second panel shows means from the later alternating **fixed/adaptive/adaptive/fixed**
JVM comparison at 32 helpers, with two 20-second samples per JVM (four per arm):

| Workload | Fixed grid | Adaptive regions | Change |
| --- | ---: | ---: | ---: |
| Uniform crowd | 120.43 TPS | 120.45 TPS | Effectively unchanged |
| Clustered farms | 82.19 TPS | 91.52 TPS | +11.35% |

All samples, the spatial-rule difference and the recipe are documented in the
[adaptive-grid report](benchmarks/ADAPTIVE_GRID_26_3.md), with a separate
[16-sample CSV](benchmarks/adaptive-grid-26.3.csv). These runs are not substituted into the
worker-sweep curve. In particular, its 119.07 TPS at 32 helpers is a different experiment
from the A/B's 120.43 TPS fixed-grid control. No adaptive 48/64-helper measurement is available.

## Environment and limits

Dual Xeon E5-2699 v3 KVM guest, 72 vCPUs exposed as two sockets × 18 cores × two threads,
two NUMA nodes, NixOS/Linux; no explicit affinity for the plotted runs. OpenJDK 25.0.4.1,
fixed 4GiB G1 heap. Minecraft 26.3, Loader 0.20.1, Fabric API 0.165.2+26.3,
Fabric Language Kotlin 1.14.2+kotlin.2.4.21, Cloth Config 26.3.159,
C2ME 0.4.2-alpha.0.116 with global parallelism four. Default 2ms worker idle spin.

Fresh seed-12345 flat worlds, 1,024 force-loaded chunks (32×32), view/simulation distance
four, midnight, spawning/time/weather cycles disabled, no players. The worker sweep and
uniform A/B use 4,096 persistent invulnerable AI zombies, four per chunk. The clustered
A/B uses a different population recipe described in its report; compare arms within
each workload, not the two workloads against one another.

After loading chunks and waiting ten seconds, summon the crowd and settle for 15 seconds.
Run `/vanadium profile 20` followed by `/vanadium benchmark 20` for each sample. Profiling
is active during the sprint in both experiments. TPS is uncapped **processing capacity**,
not a higher gameplay tick rate; normally Minecraft runs at 20 TPS. Moving mobs and JVM
warmup can change the later samples. No tail-latency improvement is claimed.

The temporary launcher's empty flat-settings decode logged `No key layers in MapLike[{}]`;
Minecraft retains the flat preset on this failed optional settings decode. It is not a
clean-startup claim. The supported integration fixture supplies explicit layers.
The external launcher remains retired tooling, not a new repository test dependency.
Full scaling JSONs/logs are preserved locally under `.vanadium/readme-scaling/`;
the adaptive run's evidence locations are in its report. Checked-in CSVs preserve timing
values; generated worlds, logs and recordings remain untracked.

There is no vanilla/no-Vanadium arm, real multiplayer, modpack, exploration or interacting
redstone/fluid-machine measurement. Neither curve promises performance on another world
or CPU. Current reproduction/profiling tools are in [development](DEVELOPMENT.md) and
[profiling](PROFILING.md). The older [26.2 report](archive/BENCHMARKS_26_2.md) remains historical
and is no longer the source of the README figure.

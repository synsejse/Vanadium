# Performance evidence

The README figure ([light](assets/performance-light.svg), [dark](assets/performance-dark.svg)) visualizes existing **Minecraft 26.2**
measurements from 2026-11-08. It does not present the 26.3 port as benchmarked.
Its editable source is SVG; exact plotted values are in [performance.csv](assets/performance.csv).
Only straight segments join measurements; there is no smoothing or extrapolation.

## What is plotted

- **Parallel ticking off/on:** 7.6 → 81.3 uncapped TPS in one 32-worker JVM, off first,
  then neighborhood-exclusion scheduling. Ratio: 81.3 / 7.6 = **10.7×** (rounded).
  The intervening removed four-color implementation (65.4 TPS) is not plotted.
  `enabled=false` retains structural mixins, synchronization and loaded-chunk indexing;
  this is not vanilla or a server without Vanadium. Fixed order and entity movement
  can bias this comparison; it is not an independently replicated estimate.
- **Worker scaling:** the five “after” zombie values in the historical worker sweep,
  16/24/32/36/48 helpers. Each point is a separate JVM, one measurement per point.
  The caller participates in addition to the configured helpers. The 48-worker result
  is included to show oversubscription; more threads are not always faster.

These are **separate experiments**, not one continuous speedup series. In particular,
the sweep's 73.8 TPS at 32 workers and the toggle's 81.3 TPS must not be substituted for
one another. The sweep was recorded during development; it is historical evidence, not
a controlled scaling curve for the current release. The archive does not pin an exact
after revision per point. No confidence interval is available.

## Hardware and workload

Dual Intel Xeon E5-2699 v3 host, KVM guest with 72 vCPUs, two NUMA nodes, NixOS/Linux
6.18.60. At measurement time the guest reported two sockets × 36 cores, one thread
per core; that guest topology did not describe the host's SMT siblings accurately.
OpenJDK 25.0.4.1, fixed 4GiB heap, G1. No controlled affinity/frequency comparison
is claimed by this figure.

Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.160.0+26.2, Cloth Config 26.2.155,
C2ME 0.4.2-alpha.0.52 with global parallelism four. Stage flags at defaults, cell size
two. Fresh seed-12345 flat worlds; view/simulation distance four, 1,024 force-loaded
chunks (32 × 32), four persistent invulnerable AI zombies per chunk (4,096 total).
No players; natural spawning, daylight and weather cycles disabled; midnight.

Each measurement is a 30-second `/vanadium benchmark` tick sprint. TPS is uncapped
**processing capacity**, not a promise of a higher gameplay tick rate. Normally Minecraft
runs at 20 TPS. The chart axes start at zero; the 20 TPS reference is marked.
Sequential runs can drift upward by about 10% as zombies move apart.

The full [historical report](archive/BENCHMARKS_26_2.md#dual-xeon-e5-2699-v3-development-vm-2026-11-08)
records the optimization sequence, other results and limitations. Its old Python
commands are archival; those harnesses have been retired. Original logs/JSON/worlds
were retained locally during the study, not committed. The chart's CSV is a transcription
of that report, not a new raw timing dataset.

## Current measurements

The 26.3 port passed unit, live integration and paired subsystem-fixture checks
([port notes](PORT_26_3.md)). Those fixtures do not establish whole-server speedup.
Real players, network traffic, exploration, modpacks and interacting machines may
behave differently. A current production claim needs a new matched-world sweep,
multiple fresh JVMs per point, alternating controls and published raw results.
See [profiling](PROFILING.md) and [development](DEVELOPMENT.md) for current tools.

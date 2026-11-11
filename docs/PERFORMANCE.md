# Performance evidence

## Worker count

Uncapped TPS by `workers`, against the same server with Lithium but without Vanadium and C2ME
("Lithium alone", one tick thread, default Lithium options). Means of four samples with their range;
[all 120 samples](benchmarks/worker-scaling-26.3.csv).

| Workers | Spread out: 4,096 zombies, four per chunk | Crowded base: 700 mixed mobs in 4×4 chunks | Combat: 600 mobs in a 34×34 arena |
| ---: | ---: | ---: | ---: |
| Lithium alone | 20.8 (20.0–21.6) | 119 (86–151) | 25.2 (23.3–26.2) |
| 2 | 50 (49–51) | 237 (210–267) | 55 (50–59) |
| 4 | 74 (69–77) | 389 (288–479) | 80 (69–84) |
| 8 | 103 (97–109) | 566 (474–658) | 122 (114–129) |
| 16 | 163 (155–170) | 782 (633–917) | 163 (151–172) |
| 24 | 193 (182–206) | **945** (671–1,266) | **175** (161–182) |
| 32 | 211 (200–220) | 933 (704–1,124) | 152 (138–163) |
| 48 (default) | **220** (215–226) | 850 (662–1,053) | 88 (81–97) |
| 64 | 196 (189–209) | 736 (565–926) | 96 (93–100) |
| 72 | 126 (120–130) | 333 (283–375) | 108 (100–114) |

- At 24 workers the three workloads run 9.3×, 7.9× and 6.9× faster than Lithium alone. Two workers
  already double it (2.0–2.4×).
- Gains per worker shrink steadily: 8 workers give about 5×, 24 give 7–9×.
- Past the 36 physical cores throughput falls. One worker per logical processor (72 here, and the
  default until this sweep) leaves none for the server thread, C2ME and the collector. It ran 38–65%
  below the best setting, and for the crowded base below four workers.
- `workers = 0` now picks two thirds of the logical processors (48 here): the peak for the
  spread-out world and 90% of it for the crowded base, but half the combat peak.
- Combat peaks earliest and loses 42% between 32 and 48 workers. Its entities write to each other
  constantly, so the likely cause is more of them waiting on each other; that drop has not been
  profiled.
- On this machine 24 workers is the best single setting: the peak for two workloads and 88% of the
  peak for the third. Elsewhere, start near two thirds of the physical cores and measure with
  `/vanadium benchmark`.

Measured 2026-11-11 on the flat-wave build (2.1.0, commit `9e61665`), `isolation = 2`. One fresh
server JVM per point and pass: an ascending pass over the worker counts, then a descending pass, then
two Lithium-alone JVMs, each with two `/tick sprint` windows of 1,000 ticks (four samples per
point). The spread-out world force-loads 32×32 chunks; the other two are the workloads below.
Environment as in [Environment and limits](#environment-and-limits). The crowded base's second
sprint window is always faster than its first (by 15–75%, with and without Vanadium), which is what
widens its ranges.

## Flat entity wave against regions

Entities tick in one wave with no regions, so throughput no longer depends on how close together
they are. These measurements compare that with the region scheduling it replaced for entities.
[All 40 samples](benchmarks/flat-entity-wave-26.3.csv).

Measured 2026-11-11 with a build that could still schedule entities in regions (`isolation = 1`)
behind a switch that has since been removed: regions and flat wave in alternating JVMs
(regions/flat/flat/regions), two vanilla `/tick sprint` windows each (four samples per
arm; 1,000 ticks, 400 for the villagers). Standalone Fabric servers with Lithium 0.26.3 and C2ME, flat
world, no players, mobs that cannot die, spawning off. Means in TPS; ranges are all four samples.

| Workload | Workers | Regions | Flat wave | Change |
| --- | ---: | ---: | ---: | ---: |
| 700 mixed mobs in 4×4 chunks | 24 | 282 (233–324) | 821 (617–1020) | 2.9× |
| 700 mixed mobs in 4×4 chunks | 8 | 335 (261–405) | 551 (385–694) | 1.6× |
| 600 mobs in 6×6 blocks (one chunk) | 8 | 107 (90–128) | 510 (352–676) | 4.8× |
| 2,000 villagers in 8×8 chunks | 24 | 88 (82–96) | 187 (139–218) | 2.1× |
| 600 mobs fighting in a 34×34 arena | 24 | 21 (20–23) | 168 (157–179) | 7.8× |

The arena is 300 zombies, 200 villagers and 100 iron golems with Resistance, walled in so they keep
fighting. Over 2,400 ticks of it the flat wave made about 41,000 cross-entity damage writes and
2.1 million queued pushes with no lock cycle, no timeout and no skipped tick. A JFR capture of that
run put 56% of tick-thread samples in entity ticks (about 26% under regions on the dense base), 0.8%
in the ownership protocol, and 0.3 s of monitor contention in total.

Samples vary by up to 40% within an arm (later sprint windows run faster), so read the factors as
sizes, not precise ratios. Region scheduling stays in use for the other stages; block entities packed
into one base are still limited by it. These workloads have no players, redstone or modded entities.

## Environment and limits

Dual Xeon E5-2699 v3 KVM guest, 72 vCPUs exposed as two sockets × 18 cores × two threads,
two NUMA nodes, NixOS/Linux; no explicit affinity. OpenJDK 25.0.4.1, fixed 4GiB G1 heap.
Minecraft 26.3, Loader 0.20.1, Fabric API 0.165.2+26.3, Fabric Language Kotlin 1.14.2+kotlin.2.4.21,
Cloth Config 26.3.159, Lithium 0.26.3, C2ME 0.4.2-alpha.0.116 with global parallelism four.
Default 2ms worker idle spin.

Fresh seed-12345 flat worlds, the mob area force-loaded, view/simulation distance four, midnight,
spawning/time/weather cycles and mob griefing disabled, no players. Mobs are summoned persistent and
invulnerable (in the arena: with Resistance instead, so that they fight), settle for 30 seconds, then
two `/tick sprint` windows run back to back in the same JVM. TPS is uncapped **processing
capacity**, not a higher gameplay tick rate; normally Minecraft runs at 20 TPS. Two windows in one
JVM are not independent replication, and no tail-latency improvement is claimed.

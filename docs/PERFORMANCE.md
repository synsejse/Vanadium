# Performance evidence

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

No worker-count sweep has been run since entities moved to the flat wave; the 8- and 24-worker rows
are the only points measured.

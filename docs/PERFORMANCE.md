# Performance evidence

## Worker count

Uncapped TPS by `workers`, against the same server with Lithium but without Vanadium and C2ME
("Lithium alone", one tick thread, default Lithium options). Means of four samples with their range;
[all 120 samples](benchmarks/worker-scaling-26.3.csv).

| Workers | Spread out: 4,096 zombies, four per chunk | Crowded base: 700 mixed mobs in 4×4 chunks | Combat: 600 mobs in a 34×34 arena |
| ---: | ---: | ---: | ---: |
| Lithium alone | 21.2 (20.6–22.5) | 187 (164–210) | 23.1 (22.1–23.7) |
| 2 | 53 (48–55) | 417 (377–448) | 72 (71–75) |
| 4 | 76 (72–79) | 586 (552–629) | 107 (102–110) |
| 8 | 113 (105–121) | 852 (787–917) | 168 (158–174) |
| 16 | 185 (175–193) | 1,177 (1,099–1,282) | 239 (231–244) |
| 24 | 227 (220–231) | 1,326 (1,190–1,449) | **244** (238–254) |
| 32 | 244 (242–245) | **1,429** (1,299–1,562) | 228 (222–236) |
| 48 (default) | **262** (252–270) | 1,374 (1,220–1,515) | 201 (189–214) |
| 64 | 244 (225–258) | 1,231 (1,111–1,370) | 202 (187–217) |
| 72 | 145 (140–153) | 706 (617–826) | 186 (170–201) |

- At 24 workers the three workloads run 10.7×, 7.1× and 10.6× faster than Lithium alone. Two workers
  already give 2.2–3.1×.
- Gains per worker shrink steadily: 8 workers give 4.6–7.3×, 24 give 7–11×.
- Each workload peaks at a different count: combat at 16–24 workers, the crowded base at 32, the
  spread-out world at 48.
- One worker per logical processor (72 here) leaves none for the server thread, C2ME and the
  collector. It ran 45% and 51% below the best setting for the spread-out world and the crowded base,
  and 24% below it for combat.
- `workers = 0` picks two thirds of the logical processors (48 here): the peak for the spread-out
  world, 96% of it for the crowded base and 82% for combat.
- Combat loses 18% between 24 and 48 workers. A profile at 48 workers showed its entity wave waiting
  on single long villager ticks, which more workers cannot shorten.
- On this machine 32 workers is the best single setting, within 7% of the peak for all three
  workloads. Elsewhere, start near two thirds of the physical cores and measure with
  `/vanadium benchmark`.

Measured 2026-11-13 on 2.1.0 at commit `f3658bf`, `isolation = 2`. One fresh server JVM per point and
pass: Lithium alone, an ascending pass over the worker counts, a descending pass, then Lithium alone
again. Each JVM runs two `/tick sprint` windows (four samples per point) of 2,000 ticks for the
spread-out world, 15,000 for the crowded base and 2,500 for combat. The spread-out world force-loads
32×32 chunks; the other two are the workloads below. Environment as in
[Environment and limits](#environment-and-limits).

- The crowded base's second sprint window is always faster than its first (by 9–22%, with and
  without Vanadium), which is what widens its ranges. The sprint report rounds to 0.01 ms per tick,
  about 1.4% at 1,400 TPS.
- An earlier sweep of this table used 1,000-tick windows, which lasted about one second on the crowded
  base and read lower there (Lithium alone: 119 TPS). Do not compare its numbers with these; the
  comparison of builds is the next section.

## Before and after the tick-path changes

Vanadium before and after the changes of
[#16](https://github.com/synsejse/Vanadium/pull/16): the section lock taken only when an entity
changes section, tick collection kept across ticks, fan-out worker wake-up, sliced spawn counting,
cell grids for crowded sections and an entity wave ordered by cost. Means of four samples with
their range, in uncapped TPS; [all 56 samples](benchmarks/tick-path-changes-26.3.csv).

| Workload | Workers | Before | After | Change |
| --- | ---: | ---: | ---: | ---: |
| Combat | 24 | 180 (178–184) | 260 (249–273) | +45% |
| Combat | 32 | 181 (175–188) | 235 (230–246) | +30% |
| Combat | 48 | 164 (159–168) | 208 (196–215) | +27% |
| Spread out | 8 | 109 (103–117) | 118 (114–123) | +9% |
| Spread out | 24 | 197 (193–205) | 231 (214–240) | +17% |
| Spread out | 48 | 226 (219–235) | 261 (258–264) | +16% |
| Crowded base | 24 | 1,313 (1,220–1,409) | 1,293 (926–1,493) | no measurable change |

- "Before" never counted mobs for the spawn caps (a bug fixed in the same change), so it paid nothing
  for that step. "After" does the count; its gains are net of that cost.
- The crowded base ticks in under 1 ms at 24 workers and is too light to show a difference; one of
  its "after" samples is a low outlier.
- At the normal 20 TPS (spread-out world, 24 workers, 60 s, two alternating servers per build) the
  average tick fell from 7.2–7.5 ms to 6.2–6.3 ms (p95 from 9.5–10.7 to 8.2–8.6; p99 13.1–18.0
  against 14.7–17.0, no clear change), and mean CPU per worker from 15% to 11–12%.

Measured 2026-11-12, `isolation = 2`: commit `2ac2660` against the merge that became `f3658bf`, in
alternating server JVMs (before/after/after/before), two `/tick sprint` windows each of 2,500 ticks
for combat, 1,500–2,000 for the spread-out world and 15,000 for the crowded base.

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

## Idle workers at 20 TPS

Tick work at the normal 20 TPS (`/vanadium profile`, 1,201 ticks over 60 s) and mean CPU per thread
(JFR thread CPU load), 4,096 zombies spread over 1,024 chunks, 24 workers unless noted, one fresh server
per row and run. Ranges are over runs.

The same build and settings ran in two states. Until about 10:35 on 2026-11-12, ticks were twice as slow
as under `/tick sprint` unless every worker spun through the gaps. From 13:43 on, idle threads cost
nothing. The guest saw no difference in its own load (only the measured server was busy, at 3–9 logical
processors), and it has no cpuidle driver and cannot see host power states, so the cause is on the host.

Slow state (morning):

| Configuration | Runs | Tick avg (ms) | p95 | p99 | CPU per worker | Server thread |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `lean` behaviour (park at tick end, wake at tick start; earlier branch default) | 2 | 12.00–12.23 | 15.9–16.6 | 27.0–29.1 | 20% | 22–23% |
| …and spin 50 ms between waves of a tick | 1 | 12.22 | 15.9 | 29.4 | 23% | 22% |
| Workers woken 0.5 / 1 / 2 / 10 ms before each tick | 1 each | 12.52 / 11.99 / 13.27 / 12.05 | 15.4–18.7 | 24.5–34.9 | 21 / 21 / 25 / 39% | 21–24% |
| Workers spin through everything (`hot` workers; `workerSpinMicros=60000`) | 2 | 6.03–6.57 | 8.0–8.5 | 10.6–12.1 | 99% | 12–13% |

Fast state (afternoon), alternating configurations:

| Configuration | Runs | Tick avg (ms) | p95 | p99 | CPU per worker | Server thread |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `lean` | 6 | 6.26–6.80 | 8.3–9.4 | 13.5–18.3 | 11–12% | 12–13% |
| Server thread spins the last 1 ms (workers woken 2 ms early) | 1 | 6.37 | 8.6 | 15.5 | 15% | 14% |
| Server thread spins the last 5 ms | 5 | 6.03–6.97 | 7.8–9.4 | 12.5–17.5 | 11–13% | 21–23% |
| Server thread spins the last 5 ms, workers woken 2 ms early | 4 | 5.93–7.05 | 7.5–10.2 | 12.4–18.6 | 14–16% | 21–23% |
| Server thread spins through the gap (`balanced`) | 4 | 6.06–6.53 | 7.5–8.8 | 10.9–16.3 | 11–15% | 98% |
| Workers woken 25 ms before each tick | 1 | 5.83 | 7.7 | 11.1 | 60% | 11% |
| `hot` | 5 | 5.50–5.89 | 7.2–7.8 | 9.4–15.8 | 99% | 11–99% |
| 48 workers, `lean` / server spins the last 5 ms | 1 each | 5.63 / 5.14 | 8.2 / 7.2 | 16.9 / 13.7 | 9% | 11 / 19% |

- In the slow state the server thread's own CPU time per tick doubled (22% against 12% of a core), so its
  work ran slower, not only its wake-up. Waking workers up to 10 ms early, or keeping them spinning through
  the tick, did not change that; only spinning through the gaps did.
- One `hot` run (the 11% server-thread row) predates the server spin in `hot`; its workers spun alike.
- In the fast state only `hot` stands out: 10–15% lower average and lower tails, for 24 cores.
- `balanced` (the server thread spinning through the gap, about one core) is the cheapest setting that keeps
  one thread busy through the gap, as `hot` does. It was not measured in the slow state: whether one
  spinning thread suffices there is not known. A short server spin (1 or 5 ms) and early worker wake-ups
  measured no faster than nothing in either state.
- A later alternating run in the fast state, two fresh servers per mode: `lean` 6.56–7.09 ms average
  (p95 8.9–9.9), `balanced` 6.37–6.49 (8.6–9.2), `hot` 6.39–6.89 (8.8–9.9). No mode is ahead by more than
  the run-to-run spread, so `lean` is the default: it is the only one that costs nothing when it does not
  help. Try `balanced` or `hot` if `/vanadium profile` at 20 TPS shows ticks much slower than a sprint.

Measured 2026-11-12 on the branch that added `idleWorkers` (merged in
[#16](https://github.com/synsejse/Vanadium/pull/16)), `isolation = 2`, the harness of
`/vanadium profile` in a fresh server per run; environment as below.

## Environment and limits

Dual Xeon E5-2699 v3 KVM guest, 72 vCPUs exposed as two sockets × 18 cores × two threads,
two NUMA nodes, NixOS/Linux; no explicit affinity. OpenJDK 25.0.4.1, fixed 4GiB G1 heap.
Minecraft 26.3, Loader 0.20.1, Fabric API 0.165.2+26.3, Fabric Language Kotlin 1.14.2+kotlin.2.4.21,
Cloth Config 26.3.159, Lithium 0.26.3, C2ME 0.4.2-alpha.0.116 with global parallelism four.
Default `idleWorkers = lean`.

Fresh seed-12345 flat worlds, the mob area force-loaded, view/simulation distance four, midnight,
spawning/time/weather cycles and mob griefing disabled, no players. Mobs are summoned persistent and
invulnerable (in the arena: with Resistance instead, so that they fight), settle for 30 seconds, then
two `/tick sprint` windows run back to back in the same JVM. TPS is uncapped **processing
capacity**, not a higher gameplay tick rate; normally Minecraft runs at 20 TPS. Two windows in one
JVM are not independent replication, and no tail-latency improvement is claimed.

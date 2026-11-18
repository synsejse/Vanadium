# Performance evidence

## Worker count

Uncapped TPS by `workers`, against the same server with Lithium but without Vanadium and C2ME
("Lithium alone", one tick thread, default Lithium options). Means of four samples with their range;
[all 132 samples](benchmarks/worker-scaling-26.3.csv).

| Workers | Spread out: 4,096 zombies, four per chunk | Crowded base: 700 mixed mobs in 4×4 chunks | Combat: 600 mobs in a 34×34 arena |
| ---: | ---: | ---: | ---: |
| Lithium alone | 25.5 (22.9–27.0) | 206 (188–223) | 27.9 (25.6–30.6) |
| 2 | 73 (72–75) | 554 (526–581) | 86 (82–89) |
| 4 | 117 (113–122) | 778 (704–826) | 126 (123–131) |
| 8 | 176 (169–187) | 1,129 (1,020–1,250) | 192 (185–200) |
| 16 | 278 (264–301) | 1,605 (1,471–1,754) | 280 (267–301) |
| 24 | 337 (326–348) | 1,621 (909–2,083) | **304** (296–317) |
| 32 | **376** (362–383) | 1,871 (1,282–2,273) | 292 (282–303) |
| 36 (default) | 367 (340–394) | 1,887 (1,639–2,222) | 298 (286–313) |
| 48 | 371 (348–389) | **1,895** (1,639–2,174) | 288 (279–309) |
| 64 | 319 (290–353) | 1,845 (1,563–2,083) | 299 (271–322) |
| 72 | 224 (219–232) | 1,080 (962–1,266) | 291 (265–306) |

- At the default 36 workers the three workloads run 14.4×, 9.1× and 10.7× faster than Lithium alone.
  Two workers already give 2.7–3.1×, eight give 5.5–6.9×.
- Throughput levels off between 24 and 48 workers. The spread-out world peaks at 32, the crowded base
  at 48 and combat at 24, but the neighbouring counts are within noise: the default of 36 measured
  98%, 99.6% and 98% of each peak, inside the peak's sample range.
- One worker per logical processor (72 here) leaves none for the server thread, C2ME and the
  collector. It ran 40% and 43% below the best setting for the spread-out world and the crowded base.
- Combat no longer loses throughput with more workers (it lost 18% from 24 to 48 before the second
  wave): its entity wave runs on no more threads than its longest tick leaves work for, about 30
  here, and the other workers stay parked. 72 workers ran 4% below its peak.
- Against the previous default of 48 workers, 36 measured −1%, −0.4% and +3.5%: within noise on all
  three. On this machine the default is as good a single setting as any; elsewhere, start from it
  and measure with `/vanadium benchmark`.

Measured 2026-11-14 at commit `e29ec28` (after the second wave), `isolation = 2`, no extra JVM flags
([recommended flags](#recommended-jvm-flags) add 12–65% on top). One fresh server JVM per point and
pass: Lithium alone, an ascending pass over the worker counts, a descending pass, then Lithium alone
again. Each JVM runs two `/tick sprint` windows (four samples per point) of 2,000 ticks for the
spread-out world, 15,000 for the crowded base and 2,500 for combat. The spread-out world force-loads
32×32 chunks; the other two are the workloads below. Environment as in
[Environment and limits](#environment-and-limits), with vCPUs pinned.

- The crowded base's second sprint window is always faster than its first (by 9–22% in earlier
  sweeps, with and without Vanadium), which is what widens its ranges; one first window at 24
  workers (909 TPS) is a low outlier. The sprint report rounds to 0.01 ms per tick, about 2% at
  2,000 TPS.
- The previous sweep (2026-11-13, before the second wave and before vCPU pinning) read 21.2 / 187 /
  23.1 TPS for Lithium alone and peaked at 262 / 1,429 / 244. Lithium alone is the same software in
  both, so part of the difference between sweeps is the machine; compare builds within one table
  ([before and after the second wave](#before-and-after-the-second-wave)), not across sweeps.
- An even earlier sweep used 1,000-tick windows, which lasted about one second on the crowded
  base and read lower there (Lithium alone: 119 TPS). Do not compare its numbers with these.

### Default worker count

`workers = 0` gives min(physical cores, logical processors − 1): every core gets a worker and the
server thread, which joins every wave, gets the remaining logical processor (an SMT sibling, or a core
without SMT). Uncapped TPS, mean of eight samples with their range, the same build at four explicit
counts:

| Workers | Spread out | Crowded base | Combat |
| ---: | ---: | ---: | ---: |
| 32 | 269 (260–276) | 1,418 (1,124–1,613) | 258 (244–271) |
| 35 | 261 (242–279) | 1,428 (1,220–1,639) | 259 (243–273) |
| 36 (default) | 263 (256–270) | 1,446 (1,333–1,587) | 252 (246–260) |
| 48 (previous default) | 270 (263–288) | 1,402 (1,220–1,539) | 223 (213–232) |

- 32, 35 and 36 workers are within noise of each other on every workload. 36 against 48: combat
  +13%, the crowded base +3% (within its spread), the spread-out world −3%.
- Small machines, emulated with CPU affinity (`taskset`, which the default follows) on the same host:

| CPUs | Workers | Spread out | Combat |
| --- | ---: | ---: | ---: |
| 4 cores × 2 threads (CPUs 0–7) | 3 | 87.5 (73.1–96.2) | 123 (117–131) |
| | 4 (default) | 93.9 (90.8–95.8) | 124 (119–129) |
| | 5 (previous default) | 93.0 (83.2–98.1) | 128 (119–136) |
| 4 cores × 1 thread (CPUs 0, 2, 4, 6) | 3 (default) | 84.6 (75.2–90.9) | 117 (110–124) |
| | 4 | 78.5 (74.9–80.5) | 101 (96–106) |

- Without SMT, one worker per core plus the server thread oversubscribes the cores: 3 workers beat 4
  by 8% and 16%. With SMT the server thread fits on a sibling, and 4 workers are within noise of 5.
- These are a 2.3 GHz server CPU's cores pinned to one socket, not a desktop CPU; the comparison
  between counts is what they show.

Measured 2026-11-14 at commit `a74931b` (`isolation = 2`, explicit `workers`), one fresh server JVM per
point, four rounds with the order of the counts rotated each round, two `/tick sprint` windows per JVM
(2,000 / 15,000 / 2,500 ticks as above). The host's vCPUs were pinned 1:1 to host CPUs with guest NUMA
bound to host nodes; the build predates the second wave below. Compare rows within a table, not
across them.

## Before and after the second wave

Vanadium before and after the second round of tick-path work: one worker per physical core by
default, allocation-free hot paths (`Entity.isInWall`, entity box queries, the path-type cache), no
mob count while `spawn_mobs` is false and a cheaper one otherwise, a shorter combat tail (costliest
entity kinds first, no more threads than the longest tick leaves work for, the server thread spinning
through it) and entity homes. Both builds at 36 workers, no extra JVM flags. Means of 16 samples
(eight server JVMs × two sprint windows) with their range, in uncapped TPS;
[all 96 samples](benchmarks/second-wave-26.3.csv).

| Workload | Before | After | Change |
| --- | ---: | ---: | ---: |
| Spread out | 261 (244–275) | 379 (344–427) | +45% |
| Crowded base | 1,440 (1,220–1,695) | 1,965 (1,639–2,273) | +36% |
| Combat | 237 (207–273) | 295 (272–329) | +25% |

- "After" ran faster than "before" in all eight rounds of every workload. Like windows agree: first
  windows +44% / +30% / +27%, second windows +47% / +42% / +23%.
- Both arms ran with `workers = 36`, so the new default is not part of these numbers. Against the
  previous default of 48 workers, the "before" build measured 3% faster on the spread-out world and
  13% slower in combat ([default worker count](#default-worker-count)).
- **Spawning was off**, as in every benchmark here. A dedicated server with `spawn_mobs` false now
  skips the mob count; that alone measured +14% on the spread-out world and +26% on the crowded base
  (against the build before it, eight runs per arm). With spawning on the count still runs every
  tick: the cheaper count measured +9% on the spread-out world (four runs per arm) and no change on
  the crowded base, whose 700 mobs are counted serially in both builds. A server with spawning on
  should therefore expect a smaller gain than this table shows, mostly on the crowded base. Combat
  was not measured with spawning on.

Measured 2026-11-14, `isolation = 2`: commit `a74931b` (before) against `e29ec28` (after), in
alternating server JVMs with the order of the builds reversed every round (the same rounds as the
flag runs below). Sprint windows of 2,000 ticks (spread out), 15,000 (crowded base) and 2,500
(combat). Environment as in [Environment and limits](#environment-and-limits).

## Recommended JVM flags

On Linux with more than one NUMA node (`lscpu` shows the node count), and with memory to dedicate
to the server:

```sh
java -Xms4G -Xmx4G -XX:+UseNUMA -XX:+UseTransparentHugePages -XX:+AlwaysPreTouch \
     -XX:+UseCompactObjectHeaders -jar server.jar nogui
```

Use the same value for `-Xms` and `-Xmx`, sized to what the machine can give the server; these
measurements used 4 GiB, and an earlier study found an 8 GiB heap 6% slower than 4 GiB on the
spread-out world. Leave room outside the heap for the JVM, mods and operating system; 4 GiB is the
benchmark heap, not a sizing recommendation for every modpack.

Uncapped TPS at 36 workers with the final build, eight server JVMs per arm (each the mean of its two
sprint windows) with their range; arms alternate with the order reversed every round:

| Workload | No extra flags | THP + pre-touch + compact headers | …plus `UseNUMA` | `UseNUMA` alone |
| --- | ---: | ---: | ---: | ---: |
| Spread out (series 1) | 379 (356–402) | 440 (418–461), +16% | 543 (468–613), +43% | – |
| Spread out (series 2) | 372 (353–388) | – | **613** (584–638), **+65%** | 547 (519–589), +47% |
| Crowded base | 1,965 (1,897–2,020) | 2,143 (2,042–2,277), +9% | **2,678** (2,424–2,803), **+36%** | 2,239 (1,595–2,492), +14% |
| Combat | 295 (285–313) | 291 (271–303), −1% | **329** (319–345), **+12%** | 314 (308–326), +7% |

- All four flags together were ahead of no flags in every round of every workload (32 of 32), and
  ahead of `UseNUMA` alone in 22 of 24 rounds.
- `-XX:+UseNUMA` carries most of the gain. With it, G1 hands each thread new objects from heap
  regions in its own node's memory; with [entity homes](#entity-homes) the same worker ticks the same
  entities every tick. How much of the gain comes from where entities' own objects live was not
  measured with hardware counters.
- Huge pages, pre-touch and compact headers add +16% / +9% / −1% on their own (spread out / crowded
  base / combat), and +12% / +20% / +5% on top of `UseNUMA`. Combat gains little from them, as
  before the second wave.
- At the normal 20 TPS (spread-out world, `/vanadium profile 60`, four alternating servers per arm)
  the average tick fell from 6.98 ms (6.61–7.39) to 5.15 ms (5.03–5.31), p95 from 10.97 to 7.86 ms and
  p99 from 22.7 to 14.5 ms.
- The first spread-out series' `UseNUMA` arm rose from 484 TPS in its first round to about 600 in its
  last, while the guest's page cache shrank and freed memory on the first node (1 GiB free at the
  start, 4.4 GiB later). With `UseNUMA` each node holds its share of the heap, so leave free memory
  on every node; the second series ran with that memory free from the start.

Costs and requirements:

- **The whole heap is committed at start.** `-XX:+AlwaysPreTouch` touches every page of `-Xms`:
  4.8 GiB resident (4.1 GiB of it huge pages) from startup with a 4 GiB heap, against 3.0–3.8 GiB
  after 30 seconds without it. Launch to "Done" with all four flags took 28 s on average (up to
  46 s), against 24 s without them; pre-touching takes longer with a larger heap.
- **Huge pages need the kernel's consent.** `/sys/kernel/mm/transparent_hugepage/enabled` must be
  `madvise` (as on the test machine) or `always`; with `never` the flag does nothing. Without
  pre-touch, huge pages were unreliable in an earlier study: regions first touched late fell back to
  small pages when the kernel found no free 2 MiB block.
- **`UseNUMA` needs libnuma** (`libnuma.so.1`, the `numactl` package's library). Without it the JVM
  prints `NUMA support disabled: Failed to initialize libnuma` and runs as if the flag were absent;
  the JDK on the test machine could not find it until it was put on `LD_LIBRARY_PATH`, and an
  earlier study that measured `UseNUMA` as noise ran without it. `-Xlog:gc+init` prints
  `NUMA Support: Enabled` when it works. On a single-node machine the JVM ignores the flag.
- **Compact object headers** are a product feature in Java 25
  ([JDK release notes](https://docs.oracle.com/en/java/javase/25/migrate/significant-changes-jdk-25.html),
  JEP 519). C2ME, Lithium, Fabric API and Vanadium ran with them in 60 server JVMs without a tick
  error; other mods were not tested.

When to adjust the flags:

- A machine exposing only one NUMA node: omit `-XX:+UseNUMA`. Check the node count rather than the
  socket count; the other three flags were measured only on this two-node machine.
- Memory shared with other services, or a heap you cannot afford to commit up front: drop
  `-XX:+AlwaysPreTouch` and `-XX:+UseTransparentHugePages`, keep `-XX:+UseNUMA` (its gain above came
  without them).
- A kernel with transparent huge pages set to `never` that you cannot change: omit
  `-XX:+UseTransparentHugePages`; the other flags do not depend on transparent huge pages.

Measured 2026-11-14 at commit `e29ec28`, `workers = 36`, `isolation = 2`, G1, `-Xms4G -Xmx4G` in every
arm; libnuma on the library path of every arm. Sprint windows as above. The 20 TPS runs used the
default `idleWorkers = lean`. These runs last a few minutes each; long uptimes (old-generation growth,
huge-page fragmentation) were not exercised.

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

## Entity homes

Whether an entity ticks on the same core from tick to tick ([Architecture](ARCHITECTURE.md#flat-entity-wave)).
Uncapped TPS, mean of eight server runs (each the mean of its two sprint windows) with their range;
arms alternate with the order reversed every round. Baseline: the build before homes; "homes": with
them; "homes + pinned": homes plus every worker pinned to its own core, socket by socket (measured,
not kept).

| Workload, workers | Baseline | Homes | Homes + pinned |
| --- | ---: | ---: | ---: |
| Spread out, 36 | 315.6 (310.1–324.8) | **409.7** (386.7–428.5), +29.8% | 394.4 (381.2–411.1), +24.9% |
| Spread out, 24 | 279.2 (266.8–288.1) | **329.3** (313.8–335.6), +17.9% | 318.2 (293.3–352.3), +14.0% |
| Crowded base, 36 | 1,922 (1,862–1,980) | **2,025** (1,949–2,112), +5.3% | 1,974 (1,840–2,037), +2.7% |
| Combat, 36 (homes in every wave) | 294.5 (273.1–304.3) | 278.5 (266.1–294.6), −5.4% | 288.5 (274.8–303.8), −2.0% |
| Combat, 36 (homes only with every worker, kept) | 281.5 (265.4–292.4) | 289.4 (269.1–305.3), +2.8% | – |

Guest hardware counters over one sprint per arm (`perf stat`, all threads of the server JVM), per
tick: loads served by a modified line in the other socket's cache (remote HITM), loads forwarded
from the other socket (remote forward), and cycles stalled with an L2 miss pending per L2 demand
miss.

| Workload, workers | Remote HITM | Remote forward | Stall cycles per L2 miss |
| --- | --- | --- | --- |
| Spread out, 36: baseline → homes → pinned | 36.6K → 8.3K → 4.3K | 32.8K → 6.9K → 4.1K | 124 → 102 → 92 |
| Spread out, 24 | 39.4K → 8.2K → 3.7K | 34.9K → 7.7K → 3.3K | 99 → 85 → 72 |
| Crowded base, 36 | 5.0K → 2.7K → 2.0K | 1.4K → 1.0K → 0.9K | 82 → 81 → 86 |
| Combat, 36 | 16.8K → 16.2K → 12.9K | 5.7K → 5.5K → 4.3K | 68 → – → 69 |

- In the spread-out world, 4,096 zombies each on a core of its own from tick to tick removed four
  fifths of the cross-socket transfers and a fifth of the cycles per tick (275M → 225M at 36
  workers). The entity wave took 2.04 ms instead of 2.66.
- The crowded base has few cross-socket transfers to begin with (its 700 mobs fit in the caches);
  homes gain 5% there.
- In combat the wave lasts as long as one villager's path search (about 2.8 ms) whatever the other
  threads do, and it runs on about 29 of 37 threads. Homes there changed no cache traffic and cost
  5%, so a wave with fewer threads than homes is claimed in list order.
- Pinning removed a further half of the remaining remote HITM loads but no tick time. Unpinned, the
  guest scheduler already kept busy workers on their socket: 0–5% of once-a-second samples found a
  worker on the other node.

Measured 2026-11-14 on the dual Xeon VM with vCPUs pinned 1:1 and guest NUMA bound to the host
(above), `isolation = 2`, Lithium 0.26.3, `-Xms4G -Xmx4G`, no other JVM flags. Sprint windows 2,000
ticks (spread out), 15,000 (crowded base), 2,500 (combat). The combat rows come from two separate
eight-pair series, so compare within a row.

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

## Mixed-bench tuning at 20 TPS

Candidate settings from [`runMixedBench`](PROFILING.md#mixed-whole-server-benchmark) on the dual Xeon
VM. None of them changes a default. Every arm was a fresh seed-12345 world and JVM at normal 20 TPS
pacing: 8 players, 4 bases, view/simulation distance 8/6, `workers = 24`, C2ME parallelism 10,
`isolation = 2`, fixed 4 GiB G1 heap, 1,600 warmup ticks, two 600-tick windows, JFR off. Arms were
run as a screen and then repeated in reverse order. Entity counts, mostly items, varied between runs.
The results below apply to this fixture.

- **Spawn cap 2** (`-Dvanadium.spawnParticipants=2`, experimental): in two auto/cap-2 pairs, cap 2 used
  25–32% less process CPU and 35–38% less worker CPU. Its p99 was 43–51 ms against 57–60 ms. It had
  15 of 2,400 ticks over 50 ms against 61, and none over 100 ms against two. A JFR pair attributed
  auto's spawning cost to waits on the shared spawn-state monitor. Caps 1, 4 and 8 and serial spawning
  were screened once each. Serial spawning used the least CPU, cap 1 runs spawning on the server
  thread, including synchronous structure-start lookups, and cap 4 had the largest tick (192 ms). Cap 2 is the first candidate to
  try on similar machines. `auto` stays the default until other hardware and workloads (sustained
  exploration, chunk misses) are measured.
- **250 µs slack spin** (`-Dvanadium.workerSlackSpinMicros=250`, default 500, both arms with cap 2):
  the spin reduced worker CPU by 18–23% and process CPU by 11–16% in both pairs. The tail results were
  mixed. P99 moved +4.7 ms in one pair and −4.6 ms in the other. In one simulation-distance-8 pair, CPU
  dropped 18% and p99 improved, while the worst tick rose from 80 to 99 ms.
- **G1 with ergonomic defaults.** ZGC cut collector pauses to about 1 ms per run. Whole-tick p99 still
  rose in both pairs, its count of ticks over 50 ms was 0 vs 2 in the screen and 5 vs 4 in the repeat,
  and it used 1–5% more process CPU. 8 MiB G1 regions (one sample) logged fewer pauses with the same
  slow ticks. Several remaining slow ticks and gaps overlapped G1 safepoints, but others had no logged
  collector or safepoint cause.
- **No preload by default.** In a development launch, `runColdExplosion` placed a once-per-JVM first
  `ServerExplosion` cost of 23.9 ms (22.6–52.7 ms, median of four pairs) on an entity-wave worker
  while the server thread waited. A class-only `Class.forName` control removed about half of the first
  blast's excess. The load itself took 39–61 ms on the server thread. Helper-class and first-run costs
  remained. A production launch and the startup cost of a real preload were not measured.

On the combined branch at `e6f7af7`, three fresh pairs (two at simulation distance 6 and one at 8)
used the same setup with two 1,000-tick windows per JVM. Cap 2 with 250 µs spin used 32–35% less
process CPU per tick and lowered p99 by 1.4–5.3 ms. Across 6,000 ticks per setting, defaults had
four ticks over 50 ms and tuning had three. Neither had a tick over 100 ms. All runs averaged
about 20 completion TPS, but tuning lengthened the worst completion gap by 9.6–11.5 ms in each
pair and lowered the worst rolling TPS. The longest tuned gap was 107.5 ms, including about
19 ms at a G1 safepoint before the tick began. Prefer this combination when CPU headroom matters
and verify completion gaps under your own workload.

Compare the command below against the same command without `mixed.jvmArgs`. Run one live server
at a time, alternate arms in fresh JVMs, and use at least four runs per arm (see
[spawn contention arms](PROFILING.md#spawn-contention-arms)).

```sh
./gradlew runMixedBench --no-configuration-cache -Pmixed.mode=enabled -Pmixed.sprint=false \
  -Pmixed.players=8 -Pmixed.bases=4 -Pmixed.workers=24 -Pmixed.c2me=10 -Pmixed.isolation=2 \
  -Pmixed.heap=4g -Pmixed.gc=g1 -Pmixed.gcLog=true -Pmixed.viewDistance=8 -Pmixed.simulationDistance=6 \
  -Pmixed.warmup=1600 -Pmixed.window=600 -Pmixed.windows=2 -Plive.jfr=false \
  '-Pmixed.jvmArgs=-Dvanadium.spawnParticipants=2 -Dvanadium.workerSlackSpinMicros=250'
```

Measured 2026-11-18 at commits `3481b4a` (spawning, workers, collectors) and `b6fc479` (cold
explosion). OpenJDK 25.0.4.1, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, C2ME
0.4.2-alpha.0.116, Lithium 0.26.3. Host frequency and contention were not observed. The machine is
described below.

## Environment and limits

Dual Xeon E5-2699 v3 (2.3 GHz, about 3.3 GHz with one busy core and 2.7 GHz with all busy) host
running Proxmox; the benchmarks run in a KVM guest with 72 vCPUs exposed as two sockets × 18 cores ×
two threads, two NUMA nodes and 15 GiB of memory, NixOS/Linux, transparent huge pages `madvise`. Other
VMs share the host; guest steal time stays below 1%. Since the evening of 2026-11-13 every vCPU is
pinned 1:1 to a host CPU (guest SMT siblings on host siblings), and guest NUMA node N (CPUs 0–35 and
36–71, 8 GiB each, distances 10/20) is bound to host node N, so guest NUMA is real. Measurements dated
earlier ran unpinned. No affinity inside the guest. OpenJDK 25.0.4.1, fixed 4GiB G1 heap
(`-Xms4G -Xmx4G`) and no other JVM flags unless a table says so.
Minecraft 26.3, Loader 0.20.1, Fabric API 0.165.2+26.3, Fabric Language Kotlin 1.14.2+kotlin.2.4.21,
Cloth Config 26.3.159, Lithium 0.26.3, C2ME 0.4.2-alpha.0.116 with global parallelism four.
Default `idleWorkers = lean`.

Fresh seed-12345 flat worlds, the mob area force-loaded, view/simulation distance four, midnight,
spawning (`spawn_mobs` false), time and weather cycles and mob griefing disabled, no players. Since
the second wave a dedicated server skips the mob count while `spawn_mobs` is false, so measurements
of later builds leave out a cost that a server with spawning on still pays (see
[the second wave](#before-and-after-the-second-wave)). Mobs are summoned persistent and
invulnerable (in the arena: with Resistance instead, so that they fight), settle for 30 seconds, then
two `/tick sprint` windows run back to back in the same JVM. TPS is uncapped **processing
capacity**, not a higher gameplay tick rate; normally Minecraft runs at 20 TPS. Two windows in one
JVM are not independent replication, and no tail-latency improvement is claimed.

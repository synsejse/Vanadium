<p align="center">
  <img src="docs/assets/vanadium.svg" alt="" width="96">
</p>

<h1 align="center">Vanadium</h1>

<p align="center">
  Parallel server ticking for Minecraft. Put more of your CPU to work.
</p>

<p align="center">
  <a href="https://github.com/synsejse/Vanadium/actions/workflows/gradle.yml"><img src="https://img.shields.io/github/actions/workflow/status/synsejse/Vanadium/gradle.yml?label=build&style=flat-square" alt="Build status"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.3-3f3f46?style=flat-square" alt="Minecraft 26.3">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-3f3f46?style=flat-square" alt="GPLv3 license"></a>
</p>

Vanadium parallelizes entity, block-entity, scheduled and chunk ticking, natural spawning,
and entity tracking. Entities tick in one wave across every worker wherever they are, in a crowded base
as well as across the map: each owns itself while it ticks, and what entities do to each other is locked
per interaction. The other stages run separate parts of the world concurrently: regions adapt to where
the work is, nearby work never overlaps, and each stage completes before the next.
It also optimizes chunk lookups, nearby-player indexing and packet preparation.

### Performance

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/performance-dark.svg">
  <img src="docs/assets/performance-light.svg" alt="Uncapped TPS by worker count on Minecraft 26.3 with Lithium. Spread out, 4,096 zombies: Lithium alone 21.2, Vanadium 53 at 2 workers, 227 at 24, 262 at 48, 145 at 72. Crowded base, 700 mobs in 4×4 chunks: Lithium alone 187, Vanadium 417 at 2 workers, 1,326 at 24, 1,429 at 32, 706 at 72. Combat, 600 mobs: Lithium alone 23.1, Vanadium 72 at 2 workers, 244 at 24, 201 at 48, 186 at 72.">
</picture>

Minecraft 26.3 on the dual Xeon test VM (36 cores, 72 threads), uncapped tick rate, no players. With
24 workers Vanadium ran these worlds 7–11× faster than the same server with Lithium alone. More
workers are not always better: each world peaked at a different count, and one worker per logical
processor ran 24–51% below the best setting. The default is two thirds of the logical processors;
try a few values of `workers` on your own machine. [Data, method and limits →](docs/PERFORMANCE.md)

### Getting started

Use **Minecraft 26.3**, **Fabric Loader 0.20.1+** and **Java 25+**. Build with `./gradlew build`
([setup](docs/DEVELOPMENT.md)), then place the release jar from `build/libs/` in `mods/` alongside:

- [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- [Cloth Config](https://modrinth.com/mod/cloth-config), [C2ME](https://modrinth.com/mod/c2me-fabric)
  and [Lithium](https://modrinth.com/mod/lithium) 0.26.3+

**C2ME and Lithium are required:** C2ME handles parallel generation/loading, Lithium makes each tick
cheaper, and Vanadium spreads ticks across cores. Vanadium turns off the few Lithium options whose job it
does itself in a thread-safe way, and keeps the rest safe for parallel ticks.

**Other mods that change how the server ticks are declared incompatible** (Async, DimThread, MCMT,
Moonrise, ServerCore, VMP): Vanadium owns entity ticking, the chunk lookup path and the tick loop's
stages, and a second mod rewriting them cannot be made safe from here. Content mods are fine; their
entities tick in parallel like vanilla's, and `serialEntityTypes` moves any that misbehave back to the
server thread. Test your world and modpack before deployment.

Configure `config/vanadium.toml`; use `/vanadium reload` to apply changes and
`/vanadium status` to inspect them. Worker-count changes require a restart.

### Learn more

[Configuration & commands](docs/CONFIGURATION.md) · [Development](docs/DEVELOPMENT.md) ·
[Architecture](docs/ARCHITECTURE.md) · [Profiling](docs/PROFILING.md) ·
[Report an issue](https://github.com/synsejse/Vanadium/issues)

Originally derived from [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT) through
[MCMTFabric](https://github.com/himekifee/MCMTFabric); Vanadium now develops its own architecture.
Licensed under [GPLv3](LICENSE).

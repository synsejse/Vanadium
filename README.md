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

| Crowded workload | Workers | Region scheduling | Vanadium | |
| --- | ---: | ---: | ---: | ---: |
| 700 mixed mobs in 4×4 chunks | 24 | 282 TPS | 821 TPS | 2.9× |
| 600 mobs in a one-chunk farm | 8 | 107 TPS | 510 TPS | 4.8× |
| 2,000 villagers | 24 | 88 TPS | 187 TPS | 2.1× |
| 600 mobs fighting in an arena | 24 | 21 TPS | 168 TPS | 7.8× |

Minecraft 26.3 with Lithium on the dual Xeon test VM, uncapped tick rate, no players. "Region
scheduling" is Vanadium's previous entity scheduler, which kept nearby work apart and so gained
little where mobs are packed together. [Data, method and limits →](docs/PERFORMANCE.md)

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

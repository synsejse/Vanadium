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
and entity tracking. A shared worker pool runs work in separate parts of the world concurrently:
regions adapt to where the work is, nearby work never overlaps, and each stage completes before the next.
It also optimizes chunk lookups, nearby-player indexing and packet preparation.

### Performance

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/performance-dark.svg">
  <img src="docs/assets/performance-light.svg" alt="Minecraft 26.3 benchmarks: the fixed-grid worker sweep peaks at 119.1 TPS with 32 helpers and falls to 85.9 at 64. Separate adaptive-region comparisons show unchanged uniform throughput and an 11.4% clustered-workload gain.">
</picture>

Minecraft 26.3 on the dual Xeon test VM. The fixed-grid scaling sweep and adaptive-region A/B
are separate experiments. [Data, method and limits →](docs/PERFORMANCE.md)

### Getting started

Use **Minecraft 26.3**, **Fabric Loader 0.20.1+** and **Java 25+**. Build with `./gradlew build`
([setup](docs/DEVELOPMENT.md)), then place the release jar from `build/libs/` in `mods/` alongside:

- [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- [Cloth Config](https://modrinth.com/mod/cloth-config) and [C2ME](https://modrinth.com/mod/c2me-fabric)

**C2ME is required:** it handles parallel generation/loading while Vanadium handles ticking.
**[Lithium](https://modrinth.com/mod/lithium) 0.26.3+ is supported and recommended:** Vanadium turns off
the few Lithium options that rewrite its own paths and keeps the rest safe for parallel ticks.
**VMP is incompatible.** Test your world and modpack before deployment.

Configure `config/vanadium.toml`; use `/vanadium reload` to apply changes and
`/vanadium status` to inspect them. Worker-count changes require a restart.

### Learn more

[Configuration & commands](docs/CONFIGURATION.md) · [Development](docs/DEVELOPMENT.md) ·
[Architecture](docs/ARCHITECTURE.md) · [Profiling](docs/PROFILING.md) ·
[Report an issue](https://github.com/synsejse/Vanadium/issues)

Originally derived from [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT) through
[MCMTFabric](https://github.com/himekifee/MCMTFabric); Vanadium now develops its own architecture.
Licensed under [GPLv3](LICENSE).

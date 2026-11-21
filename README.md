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

Vanadium spreads a Minecraft server's tick across every core: entities, block entities, scheduled and
chunk ticks, natural spawning and entity tracking run in parallel, while each stage keeps vanilla's
behaviour. On a 36-core server it ran busy worlds **9–14× faster** than the same server with Lithium
alone ([measurements](docs/src/content/docs/performance.mdx)).

## Getting started

Use **Minecraft 26.3**, **Fabric Loader 0.20.1+** and **Java 25+**. Build with `./gradlew build` and put
the jar from `build/libs/` in `mods/` together with
[Fabric API](https://modrinth.com/mod/fabric-api),
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin),
[Cloth Config](https://modrinth.com/mod/cloth-config),
[C2ME](https://modrinth.com/mod/c2me-fabric) and [Lithium](https://modrinth.com/mod/lithium).
Each build is tested with the C2ME and Lithium versions it pins.

Other mods that rewrite ticking (Async, DimThread, MCMT, Moonrise, ServerCore, VMP) are incompatible.
Content mods are untested: test your modpack, and move a mod's entities or block entities to the
server thread with the [serial type rules](docs/src/content/docs/configuration.mdx#serial-type-rules) if it misbehaves.

Settings live in `config/vanadium.toml`. `/vanadium status` shows them, `/vanadium reload` applies
changes, and `/vanadium benchmark` measures your server's headroom.

## Documentation

The documentation site in [`docs/`](docs) explains how Vanadium works, with interactive demos, and covers
configuration, performance, profiling and development. Run it with `cd docs && npm install && npm run dev`,
or read the pages directly:
[Overview](docs/src/content/docs/overview.mdx) ·
[Architecture](docs/src/content/docs/architecture.mdx) ·
[Configuration](docs/src/content/docs/configuration.mdx) ·
[Performance](docs/src/content/docs/performance.mdx) ·
[Profiling](docs/src/content/docs/profiling.mdx) ·
[Development](docs/src/content/docs/development.mdx)

Originally derived from [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT) through
[MCMTFabric](https://github.com/himekifee/MCMTFabric). Licensed under [GPLv3](LICENSE).

# LightSpeedRe

[English](README.md) | [简体中文](README.zh-CN.md)

LightSpeedRe is a client-side Minecraft mod that cuts modpack startup overhead across Forge, NeoForge, and Fabric. It indexes resource packs with directory-level pruning, runs resource and mipmap preparation in parallel where the loader allows, reuses verified startup and shader caches across launches, and displays total JVM-to-title launch time on the title screen.

## Supported Versions

| Minecraft | Loaders | Java |
| --- | --- | --- |
| 1.20.1 | Forge, Fabric | 17 |
| 1.21.1 | NeoForge, Fabric | 21 |
| 26.1.2, 26.2 | NeoForge, Fabric | 25 |

## Installation

Download the JAR matching your Minecraft version and loader, then place it in your instance's `mods` folder. Fabric builds also require Fabric API for the matching Minecraft version.

- [Modrinth](https://modrinth.com/mod/lightspeedre)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lightspeedre-launch-optimizations)
- [Changelog](CHANGELOG.md)

The base mod works out of the box with no extra JVM arguments.

### Optional Bootstrap Agent (Forge 1.20.1 & NeoForge 1.21.1)

Forge 1.20.1 and NeoForge 1.21.1 builds include an optional embedded Bootstrap Agent for early classloading, module resolution, archive I/O, and Mixin dispatch optimization. On PCL and Prism Launcher, LightSpeedRe prepares the local Agent on demand and provides instance-relative arguments to copy so each player can configure it locally.

## Fix a Lightspeed Agent Startup Error

If Java exits with **Error opening zip file or JAR manifest missing** or **agent library failed Agent_OnLoad** referencing `lightspeed-bootstrap-agent-*.jar`, remove the stale launcher arguments before starting Minecraft:

| Launcher | Where to edit |
| --- | --- |
| PCL2 (Windows) | Version Settings → Settings → Advanced Launch Options → JVM Arguments |
| Prism Launcher (Windows, Linux, macOS) | Edit Instance → Settings → Java → Java arguments |

1. Remove the complete `-javaagent:…/lightspeed-bootstrap-agent-*.jar` argument (including surrounding quotes) along with any associated `-Dlightspeed.agent.owner=…` and `-Dlightspeed.bootstrapCacheDir=…` entries. Keep `-Xmx`, `-Xms`, and other mods' agents intact. If the instance inherits global Java settings, check the launcher's global JVM arguments as well.
2. Clear the Lightspeed pre-launch command if it points to `.lightspeed/bootstrap/pack-compiler.cmd` or `pack-compiler.sh` (Prism: *Settings → Custom Commands*; PCL2: *Advanced Launch Options*).
3. Update LightSpeedRe. If staying on an older release, set `installBootstrapAgent = false` in `config/lightspeed-common.toml` to prevent rewriting the arguments. Save, fully restart the launcher, and launch again.

If stale entries remain, download [repair-launcher.ps1](common/src/main/resources/META-INF/lightspeed/repair-launcher.ps1), close the launcher, and run:

```pwsh
pwsh -NoProfile -File ./repair-launcher.ps1 -GameDirectory "<instance game folder>"
```

Point `-GameDirectory` to the folder containing `mods` (in Prism, usually `.minecraft` or `minecraft` inside the instance). PowerShell 7 is required.

**Modpack authors** should exclude local Agent arguments from exported profiles so recipients start cleanly with base settings and opt into the Agent locally if desired.

## Repository Layout & Building

- `common/src/main` — shared resource indexing, caching, startup worker budgeting, and metrics.
- `common/src/compat` — version- and loader-specific compatibility layers (`minecraft`, `fml-bootstrap`, `forge`, `neoforge`, `fabric`, `bootstrap`).
- `agent` — embedded Bootstrap Agent packaging module for Forge 1.20.1 and NeoForge 1.21.1.
- `targets/*` — independent Gradle builds per Minecraft version and loader (`forge-1.20.1`, `neoforge-1.21.1`, `fabric-1.20.1`, `fabric-1.21.1`, `fabric-26.1.2`, `neoforge-26.1.2`, `fabric-26.2`, `neoforge-26.2`).
- `gradle/target-conventions` — shared Gradle build, packaging, and verification conventions.

See [docs/development/build.md](docs/development/build.md) for multi-version build commands, IDE target switching, and distribution client launch options.

## License and Credits

LightSpeedRe is maintained by kltyton and builds on [Lightspeed by CCr4ft3r](https://github.com/CCr4ft3r/lightspeed). Licensed under [LGPL-3.0-or-later](LICENSE).

Report issues with your Minecraft version, loader, launcher, and relevant startup log on the [Issue Tracker](https://github.com/kltyton/LightSpeedRe/issues).

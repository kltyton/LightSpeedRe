# LightSpeedRe 2.0.0

LightSpeedRe shortens Minecraft modpack startup while preserving the Mod and initial resource reload results before the title screen is usable.

| Minecraft | Loader | Release file |
| --- | --- | --- |
| 1.20.1 | Forge | `lightspeed-1.20.1-2.0.0.jar` |
| 1.21.1 | NeoForge | `lightspeed-1.21.1-2.0.0.jar` |

Install only the JAR matching the Minecraft version and loader in the instance's `mods` directory. Each release JAR contains the bootstrap Agent; a separate Agent download is unnecessary.

The first launch uses the Mod's ordinary startup path. On supported launchers, LightSpeedRe prepares JVM arguments for a subsequent fresh JVM launch. The log marker `Lightspeed startup milestone: name=title-screen-operable elapsedMs=...` is emitted after Mod loading and the initial resource reload, on the next client tick after title initialization. Forge also supports its pre-launch Pack Compiler; NeoForge does not install that Forge-specific helper.

Startup times depend on the pack, launcher, Java runtime, heap, and cache state. Compare runs only with these inputs recorded. Report compatibility or performance regressions at [GitHub Issues](https://github.com/kltyton/LightSpeedRe/issues), including the exact Mod JAR, loader, pack version, startup log, and JVM arguments with account tokens removed.

The four-pack single-run release results, artifact hashes, and comparison limits are in [2.0.0 validation](docs/release-2.0.0-validation.md).

## 中文

LightSpeedRe 2.0.0 分别提供 Forge 1.20.1 和 NeoForge 1.21.1 的单一发行 JAR。只安装与游戏版本和加载器匹配的 JAR；启动 Agent 已内嵌，无需另下。

标题可操作标记为日志中的 `title-screen-operable`，它在模组加载和首次资源重载完成后、标题初始化的下一次客户端 tick 写出。支持的启动器会为下一次全新 JVM 启动安装 Agent 参数。Forge 的预启动 Pack Compiler 仅用于 Forge。

报告问题时请附上整合包版本、发行 JAR、Java、堆参数、缓存状态和脱敏后的启动日志。不同环境的单次结果不能直接算作稳定净提速。

四包的单次发行验收、产物哈希及图片数据的比较边界见 [2.0.0 验收记录](docs/release-2.0.0-validation.md)。

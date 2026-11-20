# LightSpeedRe

[English](README.md) | [简体中文](README.zh-CN.md)

LightSpeedRe 是 Minecraft 客户端启动优化模组。它复用上次启动留下的缓存，并在加载器允许的阶段并行准备资源，以减少整合包的启动开销。标题界面显示从 JVM 启动到菜单可操作的耗时。

## 安装

下载与你的 Minecraft 版本和加载器匹配的 JAR，放到实例的 `mods` 目录。Fabric 版还需要同一 Minecraft 版本的 Fabric API。[Modrinth](https://modrinth.com/mod/lightspeedre) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lightspeedre-launch-optimizations)

| Minecraft | 加载器 | Java |
| --- | --- | --- |
| 1.20.1 | Forge、Fabric | 17 |
| 1.21.1 | NeoForge、Fabric | 21 |
| 26.1.2、26.2 | NeoForge、Fabric | 25 |

直接安装即可使用常规模组功能，无需额外 JVM 参数。Forge 1.20.1 和 NeoForge 1.21.1 提供可选的 Bootstrap Agent。2.0.7 在 PCL 和 Prism 上按需准备本机 Agent，并提供从游戏目录出发的相对路径参数供玩家复制。

## 启动报错时如何删除 Agent 参数

如果 Java 提示 **Error opening zip file or JAR manifest missing** 或 **agent library failed Agent_OnLoad**，且报错文件名为 `lightspeed-bootstrap-agent-*.jar`，可以在打开游戏前清理旧参数。

| 启动器 | JVM 参数位置 |
| --- | --- |
| PCL2（Windows） | 版本设置 → 设置 → 高级启动选项 → JVM 参数 |
| Prism Launcher（Windows、Linux、macOS） | 编辑实例 → 设置 → Java → Java 参数 |

1. 删除**整条**指向 `lightspeed-bootstrap-agent-*.jar` 的 `-javaagent:` 参数，带引号时连同引号一起删。另删同一组设置中的 `-Dlightspeed.agent.owner=…` 和 `-Dlightspeed.bootstrapCacheDir=…`。保留内存参数和其他模组的 Agent。实例继承全局设置时，也检查启动器的全局 Java 参数。
2. 若启动前命令指向 Lightspeed 的 `.lightspeed/bootstrap/pack-compiler.cmd` 或 `pack-compiler.sh`，清空这一条命令。Prism 在「设置 → 自定义命令」，PCL2 在「高级启动选项」。
3. 更新 LightSpeedRe。继续用旧版时，在实例的 `config/lightspeed-common.toml` 中设置 `installBootstrapAgent = false`，防止旧版写回参数。保存后，完整关闭并重新打开启动器，再启动实例。

仍有残留参数时，可下载[修复脚本](common/src/main/resources/META-INF/lightspeed/repair-launcher.ps1)。关闭启动器，在脚本所在目录执行 `pwsh -NoProfile -File ./repair-launcher.ps1 -GameDirectory "<实例的游戏目录>"`。请选择包含 `mods` 的游戏目录。Prism 通常是实例内的 `.minecraft` 或 `minecraft`。此命令需要 PowerShell 7。

**整合包作者**应在发布前从导出配置中移除上述本机 Agent 设置。接收者可直接运行整合包，再在自己的实例中按需配置。

## 许可与署名

LightSpeedRe 由 kltyton 维护，源自 [CCr4ft3r 的 Lightspeed](https://github.com/CCr4ft3r/lightspeed)，采用 [LGPL-3.0-or-later](LICENSE) 许可证。

反馈问题时请附上 Minecraft 版本、加载器、启动器和相关启动日志。[问题追踪](https://github.com/kltyton/LightSpeedRe/issues)

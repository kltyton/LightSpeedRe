# LightSpeedRe

[English](README.md) | [简体中文](README.zh-CN.md)

LightSpeedRe 是面向 Forge、NeoForge 与 Fabric 的 Minecraft 客户端启动优化模组。它通过目录级资源包索引与空包剪枝、加载器允许阶段的并行资源与 Mipmap 准备、跨启动缓存与着色器二进制快照复用减少整合包启动开销，并在标题界面显示从 JVM 启动到菜单可操作的总耗时。

## 支持版本

| Minecraft | 加载器 | Java |
| --- | --- | --- |
| 1.20.1 | Forge、Fabric | 17 |
| 1.21.1 | NeoForge、Fabric | 21 |
| 26.1.2、26.2 | NeoForge、Fabric | 25 |

## 安装

下载与你的 Minecraft 版本和加载器匹配的 JAR，放到实例的 `mods` 目录即可。Fabric 版还需要同一 Minecraft 版本的 Fabric API。

- [Modrinth](https://modrinth.com/mod/lightspeedre)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lightspeedre-launch-optimizations)
- [更新日志](CHANGELOG.md)

常规模组功能开箱即用，无需额外配置 JVM 参数。

### 可选 Bootstrap Agent（Forge 1.20.1 与 NeoForge 1.21.1）

Forge 1.20.1 与 NeoForge 1.21.1 发行包内嵌可选的 Bootstrap Agent，用于加速早期类加载、模块图解析、归档读取与 Mixin 分发。在 PCL 与 Prism Launcher 上，LightSpeedRe 仅在需要时准备本机 Agent，并提供基于游戏目录的相对路径参数供玩家自行复制配置。

## 启动报错时如何删除 Agent 参数

如果 Java 提示 **Error opening zip file or JAR manifest missing** 或 **agent library failed Agent_OnLoad**，且报错涉及 `lightspeed-bootstrap-agent-*.jar`，请在启动游戏前清理旧参数：

| 启动器 | JVM 参数位置 |
| --- | --- |
| PCL2（Windows） | 版本设置 → 设置 → 高级启动选项 → JVM 参数 |
| Prism Launcher（Windows、Linux、macOS） | 编辑实例 → 设置 → Java → Java 参数 |

1. 删除**整条**指向 `lightspeed-bootstrap-agent-*.jar` 的 `-javaagent:` 参数（带引号时连同引号一起删除），并删除同一组设置中的 `-Dlightspeed.agent.owner=…` 与 `-Dlightspeed.bootstrapCacheDir=…`。保留内存参数及其他模组的 Agent。若实例继承全局设置，请同步检查启动器的全局 Java 参数。
2. 若启动前命令指向 `.lightspeed/bootstrap/pack-compiler.cmd` 或 `pack-compiler.sh`，清空该条命令（Prism 在「设置 → 自定义命令」，PCL2 在「高级启动选项」）。
3. 更新 LightSpeedRe。如需继续使用旧版，请在 `config/lightspeed-common.toml` 中将 `installBootstrapAgent` 设为 `false` 以防写回参数。保存后完整重启启动器，再启动游戏。

若仍有残留参数，可下载 [repair-launcher.ps1](common/src/main/resources/META-INF/lightspeed/repair-launcher.ps1)，关闭启动器后在脚本目录执行：

```pwsh
pwsh -NoProfile -File ./repair-launcher.ps1 -GameDirectory "<实例的游戏目录>"
```

`-GameDirectory` 请指向包含 `mods` 的游戏目录（Prism 通常是实例内的 `.minecraft` 或 `minecraft`）。该脚本需要 PowerShell 7。

**整合包作者**在导出整合包前应移除上述本机 Agent 参数，让玩家先以基础配置直接启动，再按需自行启用 Agent。

## 仓库结构与构建

- `common/src/main` — 跨版本共享的资源索引、缓存、启动线程预算与指标模块。
- `common/src/compat` — 按版本与加载器分层的兼容实现（`minecraft`、`fml-bootstrap`、`forge`、`neoforge`、`fabric`、`bootstrap`）。
- `agent` — Forge 1.20.1 与 NeoForge 1.21.1 内嵌 Bootstrap Agent 打包模块。
- `targets/*` — 各 Minecraft 版本与加载器的独立 Gradle 工程（`forge-1.20.1`、`neoforge-1.21.1`、`fabric-1.20.1`、`fabric-1.21.1`、`fabric-26.1.2`、`neoforge-26.1.2`、`fabric-26.2`、`neoforge-26.2`）。
- `gradle/target-conventions` — 共享的构建、打包与发行包校验约定脚本。

多版本构建命令、IDE 目标切换与发行包实机运行说明见 [docs/development/build.md](docs/development/build.md)。

## 许可与署名

LightSpeedRe 由 kltyton 维护，源自 [CCr4ft3r 的 Lightspeed](https://github.com/CCr4ft3r/lightspeed)，采用 [LGPL-3.0-or-later](LICENSE) 许可证。

反馈问题时请附上 Minecraft 版本、加载器、启动器与相关启动日志：[问题追踪](https://github.com/kltyton/LightSpeedRe/issues)。

## 项目来源与 AI 使用

正如我在回复 Alextwicle 时所说，我希望明确说明本项目如何使用 AI。 [Issue #34](https://github.com/kltyton/LightSpeedRe/issues/34)

LightSpeedRe 源自 CCr4ft3r 的 LightSpeed。在 1.2.0 之前，我手工完成了移植和维护，功能与代码保持与上游一致。

从 1.2.0 起，我开始在项目开发中使用 AI 工具，并在 2.0.0 版本对项目进行了全面重构。后续开发中，我也会继续使用 AI 工具。如果你很介意开发中使用 AI，或只愿意使用未借助 AI 开发的软件，可以考虑使用 1.2.0 之前的版本，或者选择其他项目。

本项目保留了原项目的提交历史、作者署名和相关许可证。感谢 CCr4ft3r 创建了 LightSpeed，为本项目打下基础。

# Changelog

## 2.0.0

### 中文

- 同时提供 Forge 1.20.1 与 NeoForge 1.21.1 发行包；每个版本均为内嵌启动 Agent 的单一 Mod JAR。
- 资源索引无法确定命中时，按资源包优先级顺序查找；移除每次查找为候选资源包批量提交任务的路径，避免大型整合包与动态模型将调度开销放大到启动关键路径。
- NeoForge 发行包接入同源启动 Agent、启动器参数安装与 `title-screen-operable` 毫秒级里程碑。Forge 保留现有 Forge 专用 Pack Compiler。
- Forge 的类直通按实际 EventBus 6.2.33 与 Forge 47.4.18+ 清理器字节码确认并启用；NeoForge 移除会触发重复 Common setup 的异步模组资源包预加载，并只在 Loader 无错误且完成后记录有效标题。两个版本移除无可辨墙钟收益的模块图重放。
- Forge 移除无可辨启动收益的形状组合缓存及其 Mixin 入口。
- 四个整合包的正式发行 JAR 单次验收及不可直接比较的历史图片数据见 [2.0.0 验收记录](docs/release-2.0.0-validation.md)。

### English

- Ship Forge 1.20.1 and NeoForge 1.21.1 as single Mod JARs with embedded startup Agents.
- Search resource packs in priority order when the index cannot decide a lookup, removing the per-pack task fanout that scales poorly in large packs with generated models.
- Integrate the startup Agent, launcher argument installation, and millisecond `title-screen-operable` milestone into NeoForge. The Forge Pack Compiler remains Forge-specific.
- Enable the verified class pass-through for EventBus 6.2.33 and Forge 47.4.18+; remove NeoForge's asynchronous mod-pack preload after it caused duplicate Common setup dispatch, and gate the title marker on completed, error-free loading. Remove module graph replay from both versions.
- Remove the Forge voxel-shape combination cache and its Mixin entry after it showed no discernible startup benefit.
- Record single-run acceptance for four modpacks in [2.0.0 validation](docs/release-2.0.0-validation.md).

## 1.21.1-1.2.4

### 中文

- 新增可选的 `lightspeed-bootstrap-agent` 双产物，通过 JVM `premain` 在 NeoForge 启动扫描前工作；普通 Mod JAR 仍可独立使用。
- 为 FML SERVICE 层发现加入 ZIP 中央目录快速否定路径，并为 SecureJarHandler 不可变 JAR 根目录加入 Bloom 资源负索引。
- 所有 ASM 补丁按目标类 SHA-256 严格匹配；未知版本、目录、多版本 JAR 和读取失败均保持原版路径并输出可诊断日志。

### English

- Added the optional `lightspeed-bootstrap-agent` artifact, loaded through JVM `premain` before NeoForge startup discovery; the ordinary mod JAR remains independently usable.
- Added a ZIP-central-directory fast-negative path for FML SERVICE discovery and Bloom negative resource indexes for immutable SecureJarHandler JAR roots.
- All ASM patches require an exact target-class SHA-256; unknown versions, directories, multi-release JARs, and read failures keep the original path with diagnostic logging.

## 1.21.1-1.2.3

### 中文

- 修复资源重载监听器包装破坏第三方监听器类型和顺序的问题，兼容 DucLib/Recrafted Creatures。
- 新增专用工作窃取重载线程池、并行资源索引和重复扫描合并，降低启动阶段的 IO 与调度开销。
- 新增可关闭的 `dedicatedResourceReloadExecutor` 配置，默认开启。

### English

- Fixed reload-listener wrapping breaking third-party listener identity and ordering, including DucLib/Recrafted Creatures compatibility.
- Added a dedicated work-stealing reload pool, parallel resource indexing, and in-flight scan deduplication to reduce startup I/O and scheduling overhead.
- Added the configurable `dedicatedResourceReloadExecutor` option, enabled by default.

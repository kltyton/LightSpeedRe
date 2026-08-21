# Changelog

## 1.20.1-1.2.4

### 中文

- 新增可选的 `lightspeed-bootstrap-agent` 双产物，通过 JVM `premain` 在 Forge 启动扫描前工作；普通 Mod JAR 仍可独立使用。
- 为 FML SERVICE 层发现加入 ZIP 中央目录快速否定路径，并为 SecureJarHandler 不可变 JAR 根目录加入 Bloom 资源负索引。
- 所有 ASM 补丁按目标类 SHA-256 严格匹配；未知版本、目录、多版本 JAR 和读取失败均保持原版路径并输出可诊断日志。

### English

- Added the optional `lightspeed-bootstrap-agent` artifact, loaded through JVM `premain` before Forge startup discovery; the ordinary mod JAR remains independently usable.
- Added a ZIP-central-directory fast-negative path for FML SERVICE discovery and Bloom negative resource indexes for immutable SecureJarHandler JAR roots.
- All ASM patches require an exact target-class SHA-256; unknown versions, directories, multi-release JARs, and read failures keep the original path with diagnostic logging.

## 1.20.1-1.2.3

### 中文

- 修复资源重载监听器包装破坏第三方监听器类型和顺序的问题，兼容 DucLib/Recrafted Creatures。
- 新增专用工作窃取重载线程池、并行资源索引和重复扫描合并，降低启动阶段的 IO 与调度开销。
- 新增可关闭的 `dedicatedResourceReloadExecutor` 配置，默认开启。

### English

- Fixed reload-listener wrapping breaking third-party listener identity and ordering, including DucLib/Recrafted Creatures compatibility.
- Added a dedicated work-stealing reload pool, parallel resource indexing, and in-flight scan deduplication to reduce startup I/O and scheduling overhead.
- Added the configurable `dedicatedResourceReloadExecutor` option, enabled by default.

# Changelog

## 1.20.1-1.2.5

### 中文

- 改为单一发布JAR：普通Mod内嵌启动Agent，首次启动自动提取，并对PCL、已启用实例JVM参数覆盖的Prism/MultiMC以及游戏目录内的隔离版本JSON进行可回滚、幂等的参数安装。
- 普通Mixin首轮即可使用不可变资源前缀索引；精确查询为`O(log E)`，目录枚举为`O(log E + K)`，不再逐次遍历命名空间全部路径。
- 新增ModuleLayer解析计划缓存：完整指纹命中时复用服务绑定后的模块根闭包，模块读取图不一致时回退原始`resolveAndBind`。
- Forge EventBus动态包装类改用其已有的直接包装加载器，避免数千个包装类重复进入GAME变换链。
- 将资源准备和安全查找合并为有背压的启动工作池，缓存I/O限制为最多两个低优先级线程；标题后手动重载恢复Minecraft原执行器。
- 缓存文件改为原子替换，并按当前Mod路径、大小和修改时间生成失效键；Agent与Mixin部分覆盖时仍保留独立回退缓存。
- 未识别启动器不会修改全局Java环境；无法自动配置时保留首轮Mixin优化并记录精确安装参数。

### English

- Switched to one distributable JAR: the ordinary Mod embeds the bootstrap Agent and installs it on first launch for PCL, Prism/MultiMC instances that already override JVM arguments, and isolated version JSON profiles inside the game directory.
- Added immutable resource prefix indexes available on the first Mixin-only launch: exact lookup is `O(log E)` and directory enumeration is `O(log E + K)`.
- Added a fingerprinted ModuleLayer resolution-plan cache that reuses the service-bound root closure and falls back to the original `resolveAndBind` whenever the reads graph differs.
- Routed Forge EventBus dynamic wrappers through EventBus's existing direct wrapper loader instead of sending thousands of generated classes through the GAME transformation chain.
- Unified resource preparation and safe lookups under one backpressured startup pool, limited cache I/O to two low-priority threads, and restored Minecraft's executor for post-title reloads.
- Made cache replacement atomic and invalidated Mod-side resource caches by path, size and modification time while retaining fallback caches for partially covered Agent views.
- Unknown launchers are never handled through global Java environment variables; Mixin optimizations remain active and the exact optional arguments are logged.

## 1.20.1-1.2.4

### 中文

- 新增可选的 `lightspeed-bootstrap-agent` 双产物，通过 JVM `premain` 在 Forge 启动扫描前工作；普通 Mod JAR 仍可独立使用。
- 为 FML SERVICE 层发现加入 ZIP 中央目录快速否定路径，并为 SecureJarHandler 不可变 JAR 根目录加入 Bloom 资源负索引。
- 将物理 JAR 的精确资源清单共享给普通 Mod，使用排序前缀查询替代重复 UnionFS 遍历、逐项 `Path.resolve` 和分散的资源列表缓存。
- 在Agent阶段缓存Forge EventBus的声明方法反射结果，避免大量对象监听器重复执行`Class.getDeclaredMethod`。
- 新增有界资源字节启动镜像：首轮记录实际成功打开的标准Mod JAR资源，后续启动在整包指纹匹配时绕过UnionFS和ZIP读取。
- 将资源索引绑定到每个资源包的精确逻辑根，使用预构建目录区间和整数句柄替代热路径二分、文件系统查表、Future等待、反射和结果数组复制；子路径资源包不会读取整JAR视图。
- 新增独立有界的原始类字节与Forge扫描元数据镜像；缓存命中仍执行原始Mixin、AccessTransformer、语言加载器和安全验证流程。
- 将Forge扫描缓存提升为按Mod聚合索引：未变化且未签名的Mod直接恢复完整`ModFileScanData`并跳过整次类遍历/ASM扫描；单个Mod变化只重建该Mod，其他Mod缓存继续命中。
- 生产实验发现相同输入存在926个转换后字节不一致结果，因此未启用转换后字节码短路缓存。
- 所有 ASM 补丁按目标类 SHA-256 严格匹配；未知版本、目录、多版本 JAR 和读取失败均保持原版路径并输出可诊断日志。

### English

- Added the optional `lightspeed-bootstrap-agent` artifact, loaded through JVM `premain` before Forge startup discovery; the ordinary mod JAR remains independently usable.
- Added a ZIP-central-directory fast-negative path for FML SERVICE discovery and Bloom negative resource indexes for immutable SecureJarHandler JAR roots.
- Shared exact physical-JAR resource entries with the ordinary mod, replacing repeated UnionFS walks, per-entry `Path.resolve`, and fragmented resource-list caches with sorted prefix queries.
- Cached Forge EventBus declared-method reflection results in the Agent, avoiding repeated `Class.getDeclaredMethod` calls while object listeners are registered.
- Added a bounded resource-byte startup image: the first run records successfully opened standard-Mod JAR resources, and later matching launches bypass UnionFS and ZIP reads.
- Bound each resource pack to an exact logical-root view, replacing hot-path binary searches, filesystem maps, future joins, reflection, and copied result arrays with immutable handles and prebuilt directory ranges.
- Added separate bounded images for raw class bytes and Forge scan metadata; cache hits still execute the original Mixin, AccessTransformer, language-loader, and security pipelines.
- Promoted Forge scan caching to per-Mod aggregate indexes: unchanged unsigned Mods restore complete `ModFileScanData` and skip the whole class walk/ASM scan, while a changed Mod alone is rebuilt and all unchanged Mod entries remain valid.
- Production verification found 926 transformed-byte mismatches for identical input keys, so transformed-bytecode short-circuit caching remains rejected.
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

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

## 1.20.1-1.2.5

### 中文

- 启动资源/扫描镜像迁移为 PackImage v2：不可变内容寻址generation、原子pointer、只读mmap、按命中校验/复制，避免每次启动把完整镜像反序列化进堆，并规避Windows映射文件无法原地替换的问题。
- 新增游戏进程前的Forge语义Pack Compiler：PCL、HMCL与Prism在没有用户自定义pre-launch命令时自动安装等待式compiler脚本，复用精确`ModClassVisitor`语义并写入scan PackImage；ATM9首编431/434个JAR、109947类为2.52秒，完整stamp命中逻辑72毫秒（独立进程墙钟158毫秒）。Forge运行时的`union:` CodeSource会按精确类条目从实际启动classpath解析唯一物理JAR，零匹配或多匹配继续fail-open。首次无Agent启动在标题后以低优先级后台预种镜像，避免把首编成本转移到下一次点击启动；脚本校验owner Mod与内嵌Agent摘要，卸载或更新后旧helper自动失效。官方启动器无pre-launch合同，继续使用运行时记录；用户命令永不覆盖。
- 新增显式opt-in启动任务ABI：任务声明线程亲和性、读写效果、前置依赖、确定性、缓存资格和耗时估计；冲突任务保持注册顺序，legacy任务保持相对顺序，失败只阻断传递依赖。该ABI不会自动跨越Forge生命周期重排未知Mod工作，当前也不把它计作现有整包启动提速。
- 完成三个大型Forge 1.20.1整包的真实矩阵：ATM9（434个JAR）暖启动中位61.744秒、DeceasedCraft（389个JAR）58.702秒、SteamPunk v15.5/Forge 47.4.1（259个JAR）37.848秒；均到达可操作标题、正常退出且Agent `failures=0`。这些结果证明通用兼容与缓存命中，不宣称达到8–9秒。
- 完成 ATM9 1.1.1 官方专用服务端门禁：保留全部418个服务端JAR并加入当前发布JAR，Pack Compiler处理416个JAR/105244类耗时3.378秒；稳态纯命令启动在71.097秒到达`server-started`，Agent十个目标补丁均成功，随后通过stdin正常停止、保存全部36个维度并以0退出。
- 完成 DeceasedCraft 纯命令标题 JVM 的标题后资源重载 QA：使用临时构建期 Java Attach Agent（不随产品发布），状态为 `ATTACHED,SCHEDULED,INVOKED,COMPLETE`；在渲染线程通过反射调用映射的 `Minecraft.m_91391_()`，`CompletableFuture` 已完成，日志确认第二次 `Reloading ResourceManager` 及完成记录，游戏退出码为 `0`。复制存档的集成服务器就绪时间为 `70.398s`，客户端世界首帧为 `94.218s`，原存档未改变。
- 将`lightspeed.refmap.json`声明为`compileJava`输出并加入单JAR分发硬门禁；即使增量构建目录中的refmap被删除，Gradle也会重新运行注解处理，不再产出缺少运行时字段映射的发布JAR。
- 资源existence/namespace/list缓存迁移为确定性的中立`.lsc` schema，只允许有界String/Boolean/PackType/Map/List/Set；旧`.ser`不再读取，未知tag、损坏、截断或不支持对象均fail-open且不会覆盖上一份有效文件。
- Forge 47.4.x冷扫描改为精确指纹保护的有界并行执行；默认按现有启动预算使用1–4线程，保留每Mod future、结果绑定、异常和超时合同，`-Dlightspeed.scanWorkers=1`可恢复串行。
- shader program binary v2改为默认开启：key覆盖最终GLSL/include输入、两阶段、attribute binding、完整VertexFormat、GPU/驱动/GLSL及binary formats；开发客户端第二次启动达到`69 hit / 0 miss / 0 failures`，`false`可回退原始link。
- 并行资源查找恢复Forge的优先级与异常合同；命中或失败会取消低优先级工作，第三方reload失败隔离改为无通配符的显式opt-in。
- PCL、HMCL和官方启动器的双文件参数安装改为事务：任一写入失败时两文件按原始字节回滚，回滚失败附加到原异常。
- 新增普通日志与JFR启动里程碑，统一记录Mod构造、标题初始化/可操作、世界首帧和专服就绪边界。
- 新增按 JVM 实现区分的启动线程预算：16逻辑线程的 Zulu/HotSpot 实测采用 LightSpeed 6线程和 JIT 4线程后，同一JAR无 profiler交错A/B平均从52.017秒降至47.879秒；GraalVM保留自身JVMCI参数，切换运行时会自动移除或恢复受管理参数。
- decoded native-image像素快照改为默认关闭：三对同JAR ATM9 A/B中开启/关闭平均为48.672/48.720秒，约399MB缓存没有标题时间收益；仍可用`-Dlightspeed.nativeImageSnapshot=true`显式实验。
- 模型输入与shader program binary快照改为显式opt-in：ATM9的ModernFix动态资源路径连续31次均绕过模型快照，而旧shader key未包含GLSL/include内容和VertexFormat身份；可分别使用`-Dlightspeed.modelInputSnapshot=true`与`-Dlightspeed.shaderProgramSnapshot=true`实验。
- 改为单一发布JAR：普通Mod内嵌启动Agent，首次启动自动提取，并对PCL、HMCL、官方启动器、已启用实例JVM参数覆盖的Prism/MultiMC以及隔离版本JSON进行可回滚、幂等的参数安装。
- 普通Mixin首轮即可使用不可变资源前缀索引；精确查询为`O(log E)`，目录枚举为`O(log E + K)`，不再逐次遍历命名空间全部路径。
- 新增ModuleLayer解析计划缓存：完整指纹命中时复用服务绑定后的模块根闭包，模块读取图不一致时回退原始`resolveAndBind`。
- Forge EventBus监听器保持原始生成包装器及其调用语义，避免改变第三方事件回调行为。
- 原始类字节镜像改为默认关闭：ATM9同一命令行A/B中完整镜像增加了8.529秒；实验性显式启用时容量仍按最大堆在64–384 MiB间自适应。
- 模型烘焙保持第三方hook的原始串行语义；仅并行化无法证明外部共享状态安全的顶层回调已移除。
- 类定义归因改为仅诊断时显式启用，普通启动不再为每个首次定义类计算模块、加载器和CodeSource统计。
- PCL安装同时写入`Setup.ini`与隔离版本JSON，并能从Java `@argfile`或隔离目录名恢复版本ID；不再执行已经错过引导阶段、无法恢复启动收益的动态attach。
- 当前进程未通过premain加载Agent时始终提示彻底重开Minecraft和启动器；仅当参数无法完整持久化时额外显示各启动器入口、完整三项JVM参数和一键复制按钮。
- 写入启动参数后自动识别仍在运行的PCL、Prism/MultiMC、HMCL或官方启动器；Windows先向已验证PID拥有的窗口发送`WM_CLOSE`，无窗口或未退出时仅强制结束该精确且已验证的启动器PID，再按原命令参数重开；Minecraft和未知进程永不作为目标。
- 将资源准备和安全查找合并为有背压的启动工作池，缓存I/O限制为最多两个低优先级线程；标题后手动重载恢复Minecraft原执行器。
- 缓存文件改为原子替换，并按当前Mod路径、大小和修改时间生成失效键；Agent与Mixin部分覆盖时仍保留独立回退缓存。
- 未识别启动器不会修改全局Java环境；无法自动配置时保留首轮Mixin优化并记录精确安装参数。

### English

- Migrated resource and scan startup images to PackImage v2 with immutable content-addressed generations, an atomic pointer, read-only mapping and per-hit verification/copying. Warm launches no longer deserialize the complete image into heap, and Windows never replaces a mapped file.
- Added a pre-game Forge semantic Pack Compiler. PCL, HMCL and Prism receive a waited pre-launch script only when no user command exists; it reuses exact `ModClassVisitor` semantics and publishes the scan PackImage. ATM9 compiled 431/434 JARs and 109,947 classes in 2.52 seconds; a full stamp hit takes 72 ms logically and 158 ms including process startup. Forge runtime `union:` code sources are resolved to one physical JAR by exact class entry over the effective launcher classpath; zero or ambiguous matches remain fail-open. A first launch without premain seeds the image in a below-normal-priority background process after the title screen, so compilation is not shifted to the next launch click. The script verifies the owner Mod and embedded Agent digest, so stale helpers become inert after uninstall or update. The official launcher has no pre-launch contract and retains runtime recording. User commands are never overwritten.
- Added an explicit opt-in startup task ABI. Tasks declare executor affinity, read/write effects, predecessors, determinism, cache eligibility and a duration estimate; conflicts preserve registration order, legacy tasks preserve relative order, and failures block only transitive dependents. The ABI never moves unknown Mod work across Forge lifecycle barriers automatically and is not counted as a measured pack-startup gain.
- Completed a real three-pack Forge 1.20.1 matrix: ATM9 (434 JARs) had a `61.744s` warm median, DeceasedCraft (389 JARs) reached `58.702s`, and SteamPunk v15.5 on Forge 47.4.1 (259 JARs) reached `37.848s`. Every run reached an operable title, exited normally and reported Agent `failures=0`. This proves generic compatibility and cache activation, not the 8-9 second target.
- Completed the official ATM9 1.1.1 dedicated-server gate with all 418 server JARs plus the release JAR. The Pack Compiler processed 416 JARs and 105,244 classes in 3.378 seconds; the steady pure-command run reached `server-started` in 71.097 seconds, applied all ten Agent targets, accepted `stop`, saved all 36 dimensions, and exited `0`.
- Completed DeceasedCraft post-title resource-reload QA in a pure-command title JVM using a temporary build-only Java Attach Agent (not shipped): state `ATTACHED,SCHEDULED,INVOKED,COMPLETE`; reflection invoked mapped `Minecraft.m_91391_()` on the render thread, the `CompletableFuture` completed, logs showed a second `Reloading ResourceManager` and its completion, and the game exited with code `0`. The copied-save run reached integrated-server readiness at `70.398s` and the first client-world frame at `94.218s`; the original save was unchanged.
- Declared `lightspeed.refmap.json` as a `compileJava` output and required it in the single-JAR distribution gate. Deleting the incremental refmap now forces annotation processing to run again instead of producing a release JAR without runtime field mappings.
- Migrated resource existence/namespace/list caches to a deterministic neutral `.lsc` schema limited to bounded String/Boolean/PackType/Map/List/Set values. Legacy `.ser` files are never read; unknown tags, corruption, truncation or unsupported values fail open without replacing the last valid file.
- Added exact-fingerprint bounded parallel cold scanning for Forge 47.4.x. It uses 1-4 workers from the existing startup budget while retaining each Mod future, result binding, exception and timeout contract; `-Dlightspeed.scanWorkers=1` restores serial execution.
- Enabled shader program binary v2 by default. Keys cover final GLSL/include inputs, both stages, attribute binding, the complete VertexFormat, GPU/driver/GLSL identity and binary formats. A second development launch restored `69/69` programs with no failures; `false` restores ordinary linking.
- Restored Forge priority and exception semantics in parallel resource lookup and made third-party reload failure isolation an explicit, non-wildcard opt-in.
- Made PCL, HMCL and official-launcher two-file profile installation transactional, restoring both original byte sequences if either update fails.
- Added ordinary-log and JFR startup milestones for Mod construction, title initialization/operability, first client-world frame and server readiness.
- Added JVM-specific startup budgeting: on the measured 16-thread Zulu/HotSpot runtime, using 6 LightSpeed workers and 4 JIT compiler threads reduced the same-JAR, no-profiler interleaved A/B mean from 52.017 to 47.879 seconds. GraalVM retains its JVMCI defaults, and profile reconciliation removes or restores the managed arguments when the runtime changes.
- Disabled the decoded native-image pixel snapshot by default: three same-JAR ATM9 A/B pairs averaged 48.672 seconds enabled and 48.720 seconds disabled, so the roughly 399 MB cache provided no title-time benefit. `-Dlightspeed.nativeImageSnapshot=true` remains available for explicit experiments.
- Made model-input and shader program-binary snapshots explicit opt-ins. ModernFix dynamic resources bypassed the model snapshot on all 31 observed ATM9 launches, while the previous shader key omitted GLSL/include content and vertex-format identity. The experiments remain available through `-Dlightspeed.modelInputSnapshot=true` and `-Dlightspeed.shaderProgramSnapshot=true`.
- Switched to one distributable JAR: the ordinary Mod embeds the bootstrap Agent and installs it on first launch for PCL, HMCL, the official launcher, Prism/MultiMC instances that already override JVM arguments, and isolated version JSON profiles.
- Added immutable resource prefix indexes available on the first Mixin-only launch: exact lookup is `O(log E)` and directory enumeration is `O(log E + K)`.
- Added a fingerprinted ModuleLayer resolution-plan cache that reuses the service-bound root closure and falls back to the original `resolveAndBind` whenever the reads graph differs.
- Preserved Forge's generated EventBus listener wrappers and their invocation behavior so third-party callbacks remain unchanged.
- Disabled the raw class-byte image by default after it added 8.529 seconds in an otherwise identical ATM9 command-line A/B; the experimental opt-in still scales its capacity from 64 to 384 MiB.
- Restored the original serial contract for third-party model-bake hooks by removing top-level callback parallelization that could not prove external shared-state safety.
- Made class-definition attribution explicitly diagnostic-only so normal launches no longer compute module, loader, and CodeSource groups for every first-time class definition.
- Persisted the PCL Agent arguments to both `Setup.ini` and the isolated version JSON, resolving the version from Java `@argfile` input or the isolated directory name; removed late dynamic attach because bootstrap work has already completed by Mod construction.
- Always request a complete Minecraft and launcher restart when the current process lacks premain; launcher-specific manual paths, the complete JVM argument line, and one-click copy appear only when automatic persistence is incomplete.
- After persisting JVM arguments, identify a matching live PCL, Prism/MultiMC, HMCL, or official launcher; Windows first posts `WM_CLOSE` to windows owned by the verified PID, then force-terminates only that exact verified launcher PID when no close surface exists, before relaunching the original command and arguments. Minecraft and unknown processes are never targets.
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

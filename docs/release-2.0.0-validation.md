# LightSpeedRe 2.0.0 单次启动验收

验收日期：2026-09-29。测试机为 AMD Ryzen 7 9700X、31.11 GiB 物理内存、Windows 原生系统。每行只运行一次全新 JVM，标题可操作时间来自该次 `title-screen-operable` 日志，不取平均。标题出现时必须已完成模组加载和首次资源重载；有加载致命错误的提前标题标记不计成绩。

| 官方整合包或实例 | Loader / Java / 堆 | Mod JAR 数 | 启动路径与缓存 | 真实 JAR 无窗口模拟 | LightSpeedRe 2.0.0 标题 |
| --- | --- | ---: | --- | ---: | ---: |
| ATM10 v8.2 | NeoForge 21.1.251 / Java 21 / 12 GiB | 492 | PCL、已有缓存 | 1.594 秒 | **58.897 秒** |
| ATM9 隔离实例 | Forge 47.4.0 / Java 21 / 12 GiB | 434 | 直接 Java `@argfile`、已有缓存 | 2.692 秒 | **39.279 秒** |
| Project Infinity 0.1，0.0.51.4-hotfix-2 | Forge 47.4.20 / Java 17 / 12 GiB | 364 | PCL、已有缓存 | 1.280 秒 | **49.846 秒** |
| New Age Science and Technology 2.0.1 | NeoForge 21.1.251 / Java 21 / 12 GiB | 262 | PCL、已有缓存 | 1.152 秒 | **32.036 秒** |

Forge 发行文件 `lightspeed-1.20.1-2.0.0.jar` 的 SHA-256 为 `8A567F189CADAB423DA1EB019B3D6E9C6C4FB2D0F5FF16E0BB7E9EB38C53667D`，内嵌 Agent 为 `66C233DE3D5660EC5377ACCE10896608C9F4933965887A9CFA3F3141269D0A3C`。NeoForge 发行文件 `lightspeed-1.21.1-2.0.0.jar` 的 SHA-256 为 `2EF775AFB5A42A7C64932464FCC4004FC31CC003A250DC5F6CDF23A084AE6F56`，内嵌 Agent 为 `6CF003E740F8F38759C45BF036C1799146F2A18A861073EB4065A3C69FA60BE4`。两个发行 JAR 的 `verifyDistribution` 均返回 `SINGLE_JAR_DISTRIBUTION_OK`，Forge JAR 含 `lightspeed.refmap.json`。

四次原始标题日志的 `elapsedMs` 依表中顺序为 `58897`、`39279`、`49846`、`32036`。ATM9 的该次客户端正常退出，`exitCode=0`；其余三包在到标题并核对无加载致命错误后关闭。无窗口模拟读取对应真实模组 JAR 的类、模型/方块状态 JSON 和 PNG 输入，但不执行模组构造、Mixin 变换、注册、模型烘焙或 OpenGL，模拟秒数不能代替标题时间。

## 最终 ATM9 的启动阶段

同一次 Forge 47.4.0 日志以新 JVM 的 `processStartEpochMs=1790638432411` 为零点。`Launching target 'forgeclient'` 仅有秒级日志时间，约在第 5.6–6.6 秒；LightSpeed 自身 `mod-construction` 标记为第 12.382 秒，**不代表全部模组构造完成**；首次 `Reloading ResourceManager` 约在第 29.6–30.6 秒；`title-screen-init` 为第 38.771 秒，下一 tick 的 `title-screen-operable` 为第 39.279 秒。由此可将当前墙钟分成早期约 6 秒、Minecraft/Forge 引导至自身构造约 6 秒、构造／注册／状态切换混合段约 17 秒、首次资源重载至标题初始化约 8–9 秒，以及标题尾段 0.508 秒。日志时间分辨率不足的边界只报约数。

旧发行包的一次有效 JFR 在 `E:\.aaabackup\智能体产物\测试结果\LightSpeedRe\20260926-startup\20260928-当前包有效JFR阶段清单.md` 中把注册混合段与资源／模型段各列为约 10.7 秒，并显示类变换、注册回调和模型准备在各段交叠。该 JFR 属于不同的 JAR 与 42.850 秒启动，不能把它的方法样本或区间直接套入本次 39.279 秒结果，也不能把 17 秒混合窗口写成可完整删除的浪费。下一轮优先核查这两段的依赖和可回收墙钟，而非直接增加 Forge 已有的构造 worker。

另一次旧包 57.833 秒方法区间记录显示：`GameData.postRegisterEvents` 为 11.765 秒，其内 73,590 次 `FMLModContainer.acceptEvent` 的同线程包含区间约 11.016 秒；中位调用为 0.0014 毫秒，最慢 100 次合计 7.055 秒，占 64.0%。这 100 次与 `ModuleClassLoader.readerToClass`、`ClassTransformer.transform` 同线程区间分别重叠 2.061 秒、0.447 秒；变换区间通常嵌在类加载区间内，不能把两项相加。Forge 47.4.0 的 `GameData` 按注册表执行 unfreeze → 模组事件 → freeze → ObjectHolder，而 `acceptEvent` 自身主要转发到事件总线。由此可排除继续调几万次空壳派发的毫秒级方案；少数实际重回调及其通用数据路径才可能回收秒级时间。该记录与 2.0.0 发行 JAR 不同，仍不能直接声称当前各方法耗时相同。

## 与用户提供的图片并列

| 整合包 | 图片原版 | 图片旧 LightspeedRe | 图片 Lightload | 本机 LightspeedRe 2.0.0 |
| --- | ---: | ---: | ---: | ---: |
| ATM10 | 80 秒 | 78 秒 | 56 秒 | 58.897 秒 |
| ATM9 | 73 秒 | 84 秒 | 56 秒 | 39.279 秒 |
| Project Infinity | 49 秒 | 48 秒 | 38 秒 | 49.846 秒 |
| New Age Science and Technology | 62 秒 | 无法进入主界面 | 38 秒 | 32.036 秒 |

图片是用户提供的旧数据，测试机注明 Intel Core i5-13400F、16 GiB；本机 CPU、内存、整合包版本、启动路径与缓存条件不同，不能直接由两列相减得到稳定净提速。本机原包对照另记录 ATM10 `79.391` 秒、Project Infinity `58.815` 秒，均为 ModernFix 的启动口径，仍不是同缓存状态的配对基准。Project Infinity 在本机首次带 Agent 的冷缓存候选曾为 `70.848` 秒，随后缓存命中和字节码适配变化；保留该原始值，不把后一次暖启动当作冷启动承诺。

两个版本的资源查找均已撤销逐包任务扇出。NeoForge 另移除了会令 NAST 重复执行 Common setup 的异步模组资源包预加载，并使加载错误时不输出有效标题成绩。原报告者的 Slag 双模组近 500 模组实例不可取得；四包验收只证明所列正式发行组合，不能代替该特定实例、世界进入、服务端或长期稳定性验收。

[TaskManager](https://github.com/Wueffi/TaskManager) 的启动计时针对 Fabric 入口点，CPU 归因为采样；本次 Forge／NeoForge 的墙钟结果不使用它的采样数值。[LightLoad](https://github.com/mynamexiaopiao/LightLoad) 是参考源码项目，不视为本项目集成或本机实测结果。

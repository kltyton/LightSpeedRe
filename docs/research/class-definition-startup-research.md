# Class Definition Startup Research

Date: 2026-08-21

## Measured boundary

The final ATM9 candidate reached the title screen in 47 seconds without a profiler and 55 seconds with JProfiler. Compared with the previous 1.2.4 snapshot, runnable CPU fell from 223.985 seconds to 214.214 seconds (`-4.4%`). The old `JarResourceIndex.lowerBound` and `ResourceMembershipIndex.indexFor` hotspots disappeared; the injected resource-list handler fell from 5.438 seconds to 4.152 seconds and `UnionFileSystem.byteChannel` fell from 6.383 seconds to 4.488 seconds.

The remaining `ModuleClassLoader.readerToClass` cost is 50.041 seconds of cumulative runnable CPU. Its `ClassLoader.defineClass` boundary is 43.523 seconds (`86%`). This is cumulative multithreaded CPU, not wall-clock time.

## Why Fabric commonly starts faster

Fabric does not use a faster HotSpot `defineClass`. Its Knot loader still obtains pre-Mixin bytes, applies Fabric transforms and Mixin on demand, then calls `defineClass` for each loaded class. The important difference is the amount of loader work and the number of classes forced through that path.

- Fabric discovery uses a `ForkJoinPool`, deduplicates paths and nested JARs, and opens each JAR primarily to parse `fabric.mod.json`. It does not perform Forge's mandatory whole-JAR class annotation scan to discover ordinary entrypoints.
- Knot uses a direct class-path/code-source model with a small metadata cache. It does not build Forge's BOOT/SERVICE/GAME module layers or route every file through SecureJarHandler UnionFS.
- Fabric's entrypoints are explicit metadata. Forge 1.20.1 additionally runs ModLauncher launch plugins, transformation-service voting, access transformers, coremods, EventBus wrapper generation, signer checks, and `ModFileScanData` construction.

Sources inspected:

- Fabric Knot class definition and Mixin path: https://github.com/FabricMC/fabric-loader/blob/b907c5b292fc062d75b6d8bf8255ac200109b992/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java
- Fabric parallel metadata discovery: https://github.com/FabricMC/fabric-loader/blob/b907c5b292fc062d75b6d8bf8255ac200109b992/src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java

Therefore a comparison between a Forge ATM9 instance and an unrelated Fabric pack is not a JVM benchmark. A useful comparison must hold the mod count, loaded class count, assets, scripts, Java runtime, storage state, and title-screen criterion constant.

## Patterns from non-Minecraft projects

### Quarkus

Quarkus constructs resource-directory maps, fully indexed resource maps, explicit generated/transformed bytecode sets, and a parent-first package set before normal class loading. Its runtime loader avoids expensive parent misses and directly selects candidate resources, but it still calls JVM `defineClass`.

Source: https://github.com/quarkusio/quarkus/blob/9d042d61b3b0bc7554f927a3f5c8ca2ca0484636/independent-projects/bootstrap/runner/src/main/java/io/quarkus/bootstrap/runner/RunnerClassLoader.java

### Jandex

Jandex moves annotation discovery out of startup and persists a compact index. Its own example indexes 5,746 Hibernate classes into a 1.7 MB file, demonstrating that semantic scan data can be much smaller than class bytes. Lightspeed's Forge scan image follows this pattern but reconstructs exact ForgeSPI records instead of introducing Jandex as a dependency.

Source: https://github.com/smallrye/jandex/blob/58d03738655d90a6521f0a9d2863651a9233d72e/doc/modules/ROOT/pages/core/indexing.adoc

### Gradle

Gradle's configuration cache serializes an entire validated phase result and skips that phase on a matching input fingerprint. The relevant lesson is to cache a complete launch-plan boundary, not isolated callbacks whose side effects cannot be replayed.

Source: https://github.com/gradle/gradle/blob/4eefc0920a9431513536d238c0194bcf30b47894/platforms/documentation/docs/src/docs/userguide/optimizing-builds/configuration-cache/configuration_cache.adoc

### Spring Boot and OpenJDK AOT cache

Spring Boot's supported AOT/CDS workflow uses an extracted application and a training run. OpenJDK JEP 483 explicitly excludes classes loaded by user-defined class loaders and rejects AOT caches when agents arbitrarily rewrite classes or append loader search paths. Forge's transformed Mod classes violate both assumptions, so changing only the Java version does not archive the current hot path.

Sources:

- https://github.com/spring-projects/spring-boot/blob/c329ffa25dc160a90ebe5e4b006ad4cdf89d8683/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/packaging/aot-cache.adoc
- https://openjdk.org/jeps/483

2026-09-27 复核：Oracle 的 [JDK 26 `java` 手册](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html) 和 [JDK 27 `java` 手册](https://docs.oracle.com/en/java/javase/27/docs/specs/man/java.html) 仍把 AOT 缓存描述为类与堆对象，并列出 JVMTI 选项导致归档不兼容的情况；[OpenJDK 的 AOT 类链接实现讨论](https://mail.openjdk.org/pipermail/hotspot-dev/2024-October/095670.html) 限定其类链接目标为内建 bootstrap/platform/application loader。这些资料没有给出可直接替代 Forge `TransformingClassLoader` 加可改写 Java Agent 的受支持生产路径，因此不重复无新机制的 JDK 版本切换或归档测试。

[JLS 26 第 12 章](https://docs.oracle.com/en/java/javase/26/docs/specs/jls/jls-12.html) 还要求类加载器的预取错误只在正常执行也可能发生错误的位置反映。此前过早类预取在 ATM9 遇到 `.class_manual` 生成接口未就绪，说明提前定义任意第三方类不是无副作用的通用缓存；当前恢复到客户端模组加载边界的原型尚未证明其他整合包的这一合同。

### Eclipse OpenJ9 shared classes

OpenJ9 exposes shared-class helpers specifically for custom class loaders. A loader can look up bytes using a stable token, call `defineClass`, and store the resulting class in a VM-managed shared cache. The loader remains responsible for stale-entry/version tokens. This is closer to Forge's architecture than HotSpot's current AOT cache and is the strongest next runtime experiment, but Forge, Mixin, LWJGL, Java-Agent, crash-report, and world-entry compatibility must be proven.

Source: https://github.com/eclipse-openj9/openj9/blob/38bcdf07ccd668f29bcf439b4a5b7eab473de3f5/jcl/src/openj9.sharedclasses/share/classes/com/ibm/oti/shared/SharedClassTokenHelper.java

### CRaC, GraalVM, and persistent daemons

- CRaC can skip class definition by restoring an already initialized process, but its current checkpoint engine uses Linux/CRIU. Minecraft would also need explicit recovery for GLFW/OpenGL/OpenAL, files, watchers, and network state.
- GraalVM Native Image normally forbids runtime class definition. Its runtime class-loading support is experimental and requires an open type world, which removes the closed-world assumptions that provide most native-image benefit. Dynamic ModLauncher/Mixin behavior remains a poor fit.
- A Gradle-style resident daemon keeps classes defined, but Minecraft's global registries, native libraries, render/audio threads, and mod static state do not provide a generic reset contract. A persistent game JVM is a different product rather than a safe Agent optimization.

Sources:

- https://github.com/openjdk/crac
- https://github.com/oracle/graal/blob/7115ecd7d2d8aaab0e8233c3c5944f25dbb96aa3/substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/hub/RuntimeClassLoading.java

## Recommended next work

1. Do not add an OpenJ9 backend unless ModLauncher and every participating transformation service gain an upstream, generic signing/class-loader contract. The verified Semeru 21 experiment failed first at ModLauncher's explicit guard and then at a transformation service's dependency on HotSpot `ClassLoader.package2certs`; no bypass remains in the product.
2. Build a complete Forge launch-plan cache around module/package routing, manifests, scan data, and immutable class-source locations. Treat it like Gradle's configuration cache: strict full-input fingerprint, one serialization model, same cold/hit execution contract, and fail-open invalidation.
3. Measure loaded-class counts and `defineClass` CPU by loader/module. If a small set of eager Forge/Mod initialization chains forces most classes, pursue loader-level lazy construction only for contracts proven order-independent. Do not generically defer arbitrary Mod constructors.
4. For a separate Linux-only product, evaluate a CRaC checkpoint immediately before native client initialization or with explicit native-resource recovery. This is the plausible route to sub-30-second restore times, but it is not a Windows Forge Mod feature.

Do not retry transformed-byte short-circuit caching without a loader protocol that records and replays every transformer/plugin side effect. The ATM9 experiment already produced 926 output mismatches for identical input keys.

## Implemented follow-up: Fabric-like explicit scan aggregates

The recommended launch-plan direction was partially implemented at Forge's core scan boundary. The first launch still performs Forge's complete class verification and ASM scan, then stores one exact neutral `ModFileScanData` aggregate per Mod. Later launches restore unchanged unsigned Mod aggregates before `ModFile.scanFile`; current Mod metadata and language-loader callbacks still execute. Per-Mod ZIP central-directory identities allow changed-Mod-only rebuilds.

Against the preceding per-class cache snapshot, two JProfiler samples measured:

- `AbstractJarFileModProvider.scanFile`: `-89%` to `-90%` runnable CPU;
- `Jar.verifyPath`: `-88%` in both comparisons;
- one sample reduced `UnionFileSystem.byteChannel` by `38.9%` and `ModuleClassLoader.readerToClass` by `6.8%`;
- whole-start runnable CPU ranged from `-1.13%` to `+1.37%` because unrelated ModuleLayer, UnionFS, native-image and scheduling costs moved in the opposite direction;
- ordinary title time remained 47-48 seconds, while profiled runs varied from 54 to 62 seconds.

This approaches Fabric's metadata-first discovery behavior for unchanged Mods without replacing Forge's module, transformation, entrypoint, or language-loader contracts. It proves removal of the targeted scan phase, not a statistically significant whole-start speedup. The remaining maximum cost is still JVM class definition.

## Generalized class-loading attribution and GitHub implementation survey

A follow-up used the exact final production snapshot and a separate Java 17 JFR launch to distinguish a loader-general optimization from an ATM9-only class list. The JFR launch enabled every `ClassLoad` and `ClassDefine` stack, so its 60.434-second title time includes observer overhead and is not a replacement for the normal 47-48-second timing evidence.

At the title timestamp, `jdk.ClassLoadingStatistics.loadedClassCount` was 170,231. The process remained open after the title and ended at 170,302, so only 71 additional classes appeared after the measured boundary. The final class-loader statistics reported 156,810 classes under `cpw.mods.modlauncher.TransformingClassLoader`; the exact percentage is approximate because hidden/generated classes have separate accounting, but the result clearly places the dominant population in Forge's GAME loader rather than in the bootstrap or application loader.

The exact-final JProfiler snapshot measured 217.156 seconds of runnable CPU. `ModuleClassLoader.readerToClass` accounted for 51.450 seconds, of which 46.085 seconds (`89%`) crossed into `ClassLoader.defineClass`. Its caller distribution was broad:

- ordinary `ClassLoader.loadClass` accounted for `87%` of `readerToClass` CPU;
- reflective `Class.forName` accounted for `7.1%`, including Forge EventBus wrapper creation;
- the filtered backtrace still contained at least 588 caller branches below the display cutoff;
- one visible static-initializer example was `RechiseledCreate.<init> -> rechiseled.create.Blocks.<clinit> -> Create.AllBlocks.<clinit>`, but it represented only about `1%` of the class-definition hotspot.

The whole-start call tree showed the reusable framework boundary more clearly: ForkJoin workers used `73%` of runnable CPU, `ModContainer` transition handling used `53%`, `FMLModContainer.constructMod` used `45.6%`, and reflective Mod construction used `43.2%`. Client sprite/resource initialization was a separate `7.1%` branch. CristelLib and Mekanism constructors were visible examples in this ATM9 run, but hard-coding either one would not generalize.

### GitHub approaches that actually touch the class-definition boundary

| Approach | What the source implements | Forge 1.20.1 applicability |
|---|---|---|
| Eclipse OpenJ9 shared classes | `SharedClassTokenHelper` lets a custom loader look up VM-managed shared bytes, call `defineClass`, then store the defined class under a loader-owned token. | The only surveyed VM feature explicitly designed for custom loaders. It still needs a ModLauncher-specific token and side-effect protocol. OpenJ9's default BCI mode does not store classes modified by Java/JVMTI agents; final transformed-byte reuse therefore remains unproven for this loader. |
| LunNova CachingClassLoader | Replaces legacy LaunchWrapper and persists transformed class bytes between Forge 1.10.2 server starts. | A direct historical Minecraft precedent, but its README requires manual invalidation when transformer-affecting configuration changes. The modern ATM9 experiment produced 926 different outputs for identical input keys and has additional ModLauncher plugin/audit side effects, so this implementation model is unsafe here. |
| Quarkus RunnerClassLoader | Prebuilds package/resource maps and explicit generated/transformed bytecode sets, then still calls JVM `defineClass`. | General evidence for eliminating lookup misses and reducing the number of classes forced through the loader. Lightspeed already adopted the resource-index half; Quarkus does not remove HotSpot class definition. |
| SecureJarHandler parallel loading | `ModuleClassLoader` calls `ClassLoader.registerAsParallelCapable()` and locks by class name. | Already present. Adding more class-loading threads cannot unlock a missing JVM feature and previously increased ATM9 startup time. |
| OpenJDK CRaC | Restores a checkpointed process whose classes are already defined. | It bypasses repeated definition, but the current implementation is Linux/CRIU-based and Minecraft would need explicit GLFW/OpenGL/OpenAL, watcher, file and network recovery. It is a different product mode, not a normal Windows Mod. |
| HotSpot AOT/CDS and GraalVM | HotSpot JEP 483 excludes user-defined-loader classes and arbitrary class-rewriting agents; GraalVM runtime class loading is an experimental open-world mode. | Neither is a drop-in path for dynamic `ModuleClassLoader + ModLauncher + Mixin` classes. |

Sources:

- OpenJ9 custom-loader helper and test implementation: https://github.com/eclipse-openj9/openj9/blob/38bcdf07ccd668f29bcf439b4a5b7eab473de3f5/jcl/src/openj9.sharedclasses/share/classes/com/ibm/oti/shared/SharedClassTokenHelper.java and https://github.com/eclipse-openj9/openj9/blob/38bcdf07ccd668f29bcf439b4a5b7eab473de3f5/test/functional/cmdLineTests/shareClassTests/utils/src/CustomCLs/CustomTokenClassLoader.java
- Legacy Minecraft transformed-class cache: https://github.com/LunNova/CachingClassLoader/blob/53c02d1a9edbec7c6cbdcce7ad01c8c243dc2996/README.md
- SecureJarHandler parallel class loader: https://github.com/McModLauncher/securejarhandler/blob/ab1d9f4cf60cc66b6ff648795238fd71e5448b91/src/main/java/cpw/mods/cl/ModuleClassLoader.java
- Quarkus runner class loader: https://github.com/quarkusio/quarkus/blob/478e5fe6c974b9cefce07598e79db6359a1ab7d0/independent-projects/bootstrap/runner/src/main/java/io/quarkus/bootstrap/runner/RunnerClassLoader.java
- CRaC prerequisites: https://github.com/openjdk/crac/blob/5c5e92d05e9db8f00cf06f96247b5da2289dfca4/README.md
- HotSpot class-loading AOT boundary: https://openjdk.org/jeps/483

### General and pack-specific boundaries

The following parts are general across Forge packs that use the same loader contract:

1. Count first-time classes by loader, module/JAR source and startup phase.
2. Attribute `readerToClass` and `defineClass` to the first caller outside ClassLoader/ModLauncher internals.
3. Rank framework triggers such as Mod construction, EventBus wrapper generation, registration dispatch and client resource reload.
4. Apply any cache or lazy policy at a verified module/JAR contract boundary, never by a hard-coded class-name list.

The following parts are pack-specific and must be re-profiled:

1. Which Mod constructors and static initializers dominate.
2. Which classes may be delayed without changing registration order, event subscription, side effects or reflective discovery.
3. The actual reduction in title time after a class or module is made lazy.

Therefore the method and the loader-level measurement surface are general, while the final lazy-initialization allowlist is not. The next safe implementation should be an opt-in, bounded attribution mode in the bootstrap layer that records counts by module/JAR and initiating framework phase with negligible overhead. It should first be run on at least two materially different Forge packs before any common lazy policy is enabled by default.

## Implemented single-JAR follow-up

Version 1.2.5 keeps the ordinary GAME-layer changes as Mixins and embeds the bootstrap Agent inside the one distributable Mod JAR. The first launch extracts the exact embedded Agent and installs per-instance arguments for supported launchers; later launches retain premain access without a second user-installed file.

The remaining P1/P2 implementation adds:

- immutable resource prefix indexes with `O(log E + K)` listing;
- a fingerprinted ModuleLayer service-closure plan that verifies the resolved reads graph and fails back to `resolveAndBind`;
- direct EventBus wrapper generation through EventBus's existing non-ModLauncher factory path;
- one bounded startup CPU pool with nested lookup backpressure and at most two low-priority cache I/O workers.

## 2026-08-22 framework work-elimination follow-up

- Enabling the existing JDK 21 `Resolver.makeGraph` ArrayList-to-HashSet rewrite on the actual retransformed Oracle/Adoptium bytecode was rejected by runtime A/B. Two fully warm runs regressed to 45.695 and 45.808 seconds, with the ModLauncher-to-launch-target interval increasing from 9.661 to 11.063 seconds. The runtime fingerprint support and smoke path were removed.
- Registry-aware ObjectHolder routing retained Forge ordering/fallback semantics, routed 162 filtered calls, skipped 665,896 unrelated handlers, and reported no failures. Together with SecureJar package/index reuse and shared path validation, the final fully warm title runs reached 40.412, 39.800, and 39.762 seconds; the final build also entered and rendered a singleplayer world.

The class-loader boundary remains explicit: `ModuleLayerHandler`, SecureJarHandler, Forge Scanner and EventBus bootstrap classes cannot be transformed by an ordinary Mod Mixin because they are already defined outside the GAME transforming loader. The embedded premain Agent handles only these exact SHA-256-supported bootstrap targets.

## 2026-08-22 JProfiler follow-up

A complete 47-second GraalVM JFR loaded through JProfiler recorded 170,949 loaded classes, including 153,028 under Forge's `TransformingClassLoader`. The runnable call tree assigned 10% to Mod construction, 10% to the model completion event, 5.9% to sprite resource loading and 5.4% to module resolution. The warm Agent summary showed the 64 MiB raw class image saturated at 16,320 hits and 60,447 misses while recording no additional bytes.

Class-definition attribution is absent from ordinary production launches. The previously unconditional top-level model-bake parallelism was removed after a scheduling perturbation spent 141 seconds without reaching the title screen inside a non-thread-safe third-party bake hook map. Future model concurrency must split pure preparation from ordered external-hook commit.

The subsequent same-instance A/B rejected the larger raw class image as a default optimization. A filled 396 MB image reached the ModernFix title marker in 48.424 seconds; bypassing raw class-image loading reached it in 39.895 seconds with the same Mod JAR and no model-bake or linkage errors. Avoided ZIP reads did not compensate for loading/indexing the image while all transforms and `defineClass` work still executed. The image is therefore experimental and disabled by default.

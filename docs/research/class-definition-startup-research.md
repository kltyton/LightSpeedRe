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

1. Run an isolated Semeru/OpenJ9 Java 17 ATM9 branch and integrate `SharedClassTokenHelper` with `ModuleClassLoader` only if the unmodified pack reaches the title screen and a world. This is the only surveyed route that explicitly supports custom-loader VM shared classes without process restoration.
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
- OpenJ9 bytecode-instrumentation behavior: https://github.com/eclipse-openj9/openj9-docs/blob/e49e5962c5d9792ae0af36c88f63dd06b6d827f1/docs/xxshareclassesenablebci.md
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

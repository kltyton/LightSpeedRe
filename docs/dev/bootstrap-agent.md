# Embedded Lightspeed Bootstrap

Lightspeed 1.2.5 is distributed as one Forge Mod JAR. The same file contains:

- the ordinary GAME-layer Mixins, which work on the first launch;
- a dependency-free bootstrap Agent stored at `META-INF/lightspeed/bootstrap-agent.jar`;
- a client-side installer that extracts the Agent and configures supported per-instance launcher profiles for later launches.

Users do not install a second JAR. Lightspeed never writes `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, or another global Java setting.

## Startup sequence

On the first launch, the ordinary Mod provides the Mixin-side resource prefix indexes, path caches and bounded resource-reload scheduler. In the background it:

1. verifies and extracts the embedded Agent to `.lightspeed/bootstrap/` using a SHA-256 versioned file name;
2. removes stale Lightspeed Agent arguments and obsolete archive arguments from older releases;
3. installs the current owner, cache-directory and `-javaagent` arguments atomically;
4. on non-Graal HotSpot runtimes with at least eight logical processors, installs a measured, owned startup budget for the LightSpeed pool and HotSpot compiler threads.

The installer currently supports:

- PCL per-version `PCL/Setup.ini` (`VersionAdvanceJvm`);
- Prism Launcher and MultiMC `instance.cfg` only when the instance already has `OverrideJavaArgs=true`; inherited global JVM arguments are never replaced;
- HMCL instances with a valid `.hmcl/config/instance-game-settings.json` marker; the installer updates both instance `jvmOptions` and the matching version JSON while preserving unknown fields and inherited-setting metadata;
- official Minecraft Launcher profiles whose `lastVersionId` and effective `gameDir` match the running game, plus their matching `versions/<version>/<version>.json` manifest;
- an isolated Minecraft version JSON stored directly in the active game directory.

Launchers that rebuild or ignore these surfaces cannot be modified safely by a Mod. An HMCL custom run directory is also left unsupported when no marker-backed instance root can be validated. After changing a supported profile, Lightspeed walks the current process ancestry and restarts only a matching PCL, Prism/MultiMC, HMCL or official launcher process with its original command and arguments. Windows first posts `WM_CLOSE` only to top-level windows owned by the verified launcher PID; when the launcher exposes no window or does not exit, the explicitly authorized fallback force-terminates that exact verified PID before relaunch. Minecraft and unknown processes are never targeted. A launcher that already exited needs no refresh, while an identity mismatch keeps the title-screen manual restart advice.

Every reconciliation preserves unrelated launcher arguments and removes only Lightspeed-owned or obsolete arguments. Profile writes use an adjacent temporary file and atomically replace the original; no persistent backup is created. All supported Java runtimes use the same Agent-only launch profile.

## Why Mixins cannot replace every bootstrap patch

Ordinary Mod Mixins are registered for classes subsequently defined by ModLauncher's GAME `TransformingClassLoader`. The following classes are already defined by the application or `MC-BOOTSTRAP` loader before that point:

- `cpw.mods.modlauncher.ModuleLayerHandler`;
- `cpw.mods.cl.ModuleClassLoader` and SecureJarHandler internals;
- Forge `Scanner` and transformation-service discovery;
- Forge EventBus `EventBus` and `ModLauncherFactory`.

Jar-in-Jar changes packaging, not JVM startup order. A bundled `Premain-Class` is inert unless the JVM receives `-javaagent` before `main`. A Mod-provided transformation service or coremod also transforms later GAME classes and cannot rewrite these already-defined bootstrap classes.

Therefore the implementation is split by the real class-loader boundary:

- ordinary Mixins own `PathPackResources`, `FilePackResources`, resource priority lookup and Minecraft reload scheduling;
- the embedded Agent owns SERVICE discovery, SecureJar indexing, raw class bytes, Forge scan metadata, EventBus bootstrap classes and ModuleLayer resolution.

For PCL instances, installation writes both `PCL/Setup.ini` and the isolated version JSON. PCL keeps per-version settings in process memory, so a launcher process that was already open can ignore an external `Setup.ini` update on the next click. The installer resolves `--version` from expanded process arguments, Java `@argfile` content, `sun.java.command`, or the validated isolated directory name before writing the redundant version JSON. Lightspeed does not attempt late dynamic attach: by Mod construction time the bootstrap targets have already executed, so retransformation cannot recover the missed startup work.

## P1 and P2 algorithms

### Immutable resource prefix index

Resource paths are sorted and deduplicated once. Exact membership is `O(log E)`, while a directory listing is `O(log E + K)` for `E` indexed paths and `K` returned paths. The same structure is used for ZIP and path-backed packs. Mixin fallback caches remain independent even when the Agent handles only part of a logical resource view.

The exact SecureJar index also derives the class package set used by `Jar.getPackages`. Forge 47.4.x therefore enumerates each physical JAR once for both package discovery and later resource membership instead of walking the UnionFS and then rebuilding the same index.

`PathPackResources` path validation is memoized per pack for at most 4,096 distinct listing paths. Valid paths avoid repeated regex decomposition; invalid paths preserve the original diagnostic and empty-result behavior. Fusion-owned packs still bypass the optimization completely.

### Source-segmented startup images

Resource bytes, raw pre-transform classes, and neutral scan metadata remain in three independently bounded image domains. PackImage v2 stores a versioned key/offset table and contiguous data region in immutable content-addressed generations. A tiny atomically replaced pointer selects the current generation, so Windows never replaces a mapped file. Warm startup maps the generation read-only, retains only key/slice metadata on heap, verifies each value checksum when used, and copies only hits. Entries remain logically segmented by the exact physical JAR/resource identity; a changed source is evicted before capacity accounting while unrelated segments survive Mod updates.

Mod-side existence, namespace and resource-list maps use a separate neutral `.lsc` schema. It accepts only bounded `String`, `Boolean`, `PackType`, `Map`, `List` and `Set` values, restores nested maps as concurrent maps, and rejects duplicate keys, invalid UTF-8, unknown tags, truncation and trailing bytes. Legacy Java-serialized `.ser` files are not read. Writes remain temporary-file plus sync plus atomic replacement, so an encoding failure leaves the prior valid generation intact.

### Pre-launch Pack Compiler

The packaged Agent also contains a standalone reflection-based scan compiler. On the first packaged launch, Lightspeed resolves the exact physical code sources for Forge's `ModClassVisitor`, ForgeSPI `ModFileScanData`, ASM and Guava, then atomically writes a small fail-open compiler script beside the extracted Agent. A regular CodeSource is used directly; Forge runtime `union:` locations fall back to structured JAR entry lookup over `legacyClassPath`, `java.class.path` and `jdk.module.path`, and exactly one physical JAR must contain each required class. PCL (`VersionAdvanceRun` with wait), HMCL (`preLaunchCommand` override) and Prism/MultiMC (`PreLaunchCommand` plus `OverrideCommands`) install this script only when the field is empty or already owned by Lightspeed. Existing user commands are left byte-for-byte unchanged. The official launcher exposes no equivalent pre-launch contract, so it keeps the Agent's runtime scan-recording path.

Before the game JVM is created, the helper verifies that the owner Mod still embeds the exact extracted Agent digest, validates the game/mods/cache roots, processes only top-level regular non-symlink JARs, skips multi-release or malformed inputs independently, and invokes the exact Forge visitor through the launcher's Forge/ForgeSPI/ASM/Guava JARs. A removed or updated owner makes the stale helper inert. The physical JAR source key is computed before and after every scan; changed inputs are never published. A successful compile writes the existing neutral scan metadata schema through PackImage v2. The wrapper script always exits zero, so helper failure cannot block Minecraft.

An atomic stamp covers Java version, every compiler classpath entry, and every Mod JAR path/size/mtime. It is only a performance shortcut: runtime source-key validation remains authoritative. ATM9 measurement compiled 431 of 434 JARs and 109,947 classes into a 40.19 MB image in `2.519s`. An unchanged stamp returned in `72ms` inside the helper (`158ms` including JVM process startup). `-Dlightspeed.packCompiler.workers=1|2` controls the bounded compiler; systems with at least eight processors default to two.

The real PCL ATM9 acceptance run refreshed the launcher process, waited for the installed command, and then reached an operable title with `scanHits=409`, shader `136/136` and Agent `failures=0`. PCL observed `1.680s` for the first waited compiler invocation that created the stamp; an immediate unchanged direct invocation reported `compiled=0`, `reused=431`, `skipped=3` and `101ms` inside the helper (`1.275s` including the current machine's process and wrapper startup). The following game reached the operable-title milestone in `61.744s`; this is acceptance evidence, not an 8-9 second or statistically controlled performance claim.

The first launch cannot use premain before its launcher profile is installed. After that launch reaches the title screen, Lightspeed starts the same script once in the background only when the current JVM lacks the Agent. On Windows the helper runs through `start /belownormal /wait`; this seeds the next-launch image outside the measured startup interval. A normal pre-launch therefore sees the completed stamp and only pays the fast validation path. If the background job was interrupted, the waited pre-launch invocation remains the correctness-preserving fallback.

The separate decoded native-image pixel snapshot is disabled by default. Earlier same-JAR ATM9 A/B measurements found no title-time benefit; they do not validate the current v3 protocol. An explicit fixed-pack experiment requires both `-Dlightspeed.nativeImageSnapshot=true` and a runtime-frozen 64-character SHA-256 `-Dlightspeed.nativeImageSnapshot.environment=<digest>`; disabled or invalid-environment mode does not construct, read, record, or persist that store.

Model-input snapshots remain opt-in because ModernFix dynamic resources bypassed those methods on all 31 measured ATM9 launches. Shader-program binaries are enabled by default after the v2 key was changed to include the exact post-include source passed to OpenGL for both stages, shader stage/name, attribute-binding mode, the complete `VertexFormat`, GPU/driver/GLSL identity and supported program-binary formats. A first development launch recorded `9` hits and `60` misses; the next launch restored `69/69` programs with no failures. `-Dlightspeed.shaderProgramSnapshot=false` restores the original ProgramManager link path exactly once.

### Module resolution plan

The Agent fingerprints module descriptors, service providers, roots and parent graphs. Runtime `ModuleReference` locations are intentionally excluded because they do not affect the resolution graph and some UnionFS locations change between otherwise identical launches. Existing location-sensitive plans migrate once. A cold launch records the final service-bound root closure and graph digest. A matching warm launch resolves the recorded closure without repeating service binding; if the resulting reads graph differs, it immediately runs the original `Configuration.resolveAndBind` path. Summary counters distinguish absent plans, input drift, graph mismatches and replay failures.

### EventBus listeners

Forge defines one generated listener wrapper class per callback. The Agent keeps those wrappers and their invocation behavior unchanged. Listener discovery, ordering, cancelation and generic-event filtering remain owned by `ASMEventHandler`.

### Raw class image sizing

The raw class image only replaces repeated immutable JAR byte reads; every original Mixin, access transformer, launch plugin, signer check and JVM `defineClass` operation still runs. An otherwise identical ATM9 A/B showed that loading a complete image added 8.529 seconds, so it is disabled by default. `-Dlightspeed.rawClassImage=true` remains an experimental override; when enabled, its capacity scales with the configured maximum heap from 64 MiB through 384 MiB and `lightspeed.classImageMiB` can set an explicit bound. Files contain only bytes actually observed during startup and are not preallocated.

Class-definition attribution is diagnostic-only and disabled by default. Enable it for one measurement launch with `-Dlightspeed.agent.attribution=true`; production launches do not compute module, loader or CodeSource groups for every defined class.

### Registry-aware ObjectHolder routing

Forge applies ObjectHolder handlers once per frozen registry. The original implementation iterates every handler for every registry and lets each handler reject the unrelated key. For the exact Forge 47.4.x fingerprint, the Agent builds a registry-key route once, preserves original handler order, and executes only the matching default handlers plus unknown custom handlers. Predicates that cannot be proven to be Forge's captured single-registry equality test retain the complete original iteration. Dynamic handler addition/removal invalidates the route.

### Bounded startup scheduling

Resource preparation and safe fallback lookups share one work-stealing pool. On a supported non-Graal HotSpot launch, the installer reserves capacity for Forge, the render thread and the JVM by assigning LightSpeed about three eighths of the logical processors, capped at eight workers, and HotSpot about one quarter, capped at six compiler threads. The measured 16-thread profile is `6` LightSpeed workers and `4` compiler threads. GraalVM keeps its own JVMCI ergonomics, and unsupported JVMs receive no HotSpot option. Cache I/O uses at most two low-priority threads. Nested lookups from the startup pool remain sequential, providing backpressure instead of recursively submitting more futures. After the title screen, manual reloads use Minecraft's live executor rather than the stopped startup pool.

Forge's exact 47.4.x `BackgroundScanHandler` fingerprint uses a single executor for every `ModFile.compileContent`. The Agent replaces only that executor factory with a bounded 1-4 worker pool (explicit `-Dlightspeed.scanWorkers=1` restores serial execution) and synchronizes the two existing shared-list mutation methods. Per-Mod futures, result attachment, input order, exceptions, progress ticks and timeout behavior remain Forge-owned.

### Opt-in startup task ABI

The public startup task ABI is an explicit cooperation boundary for framework-owned or third-party work that can prove its dependencies. A task declares a namespaced id, executor affinity (`CPU_PURE`, `BLOCKING_IO`, `CLASS_DEFINE`, `MAIN_THREAD`, `RENDER_THREAD`, `LOW_PRIORITY_WRITEBACK`, or `LEGACY`), opaque read/write effects, explicit predecessors, determinism, cache eligibility and an estimated duration. Read/write conflicts preserve registration order, `after` adds constraints but cannot reverse that conflict order, and all `LEGACY` tasks retain their original relative order; contradictory declarations are rejected as cycles. Plan construction is bounded to 4,096 tasks and tracks the last writer plus readers since that write for each effect instead of comparing every task pair. The validated DAG reports a saturating estimated critical path and dispatches each kind only through caller-supplied executors.

This ABI does not automatically move unknown Mod constructors, events, registry writes, transformers or render work across Forge barriers. Freezing the registry is idempotent, but the returned plan is single-use so non-idempotent startup effects cannot be replayed or overlap another execution. A failing task blocks its transitive dependents while unrelated branches finish and the aggregate future reports the failure. `cacheable` is currently eligibility metadata only; task actions are not persisted or replayed. Consequently, the ABI is a safe foundation for future cooperating integrations, not a claimed title-time improvement for existing packs.

Parallel resource queries consume futures in original pack-priority order. A winner cancels lower-priority work; an exception that sequential Forge would observe is rethrown with its original runtime identity instead of being logged and skipped. Resource-reload failure isolation is disabled by default and rejects wildcard configuration. It can be enabled only with explicit listener class prefixes.

Startup measurement logs and emits a JFR `com.ccr4ft3r.lightspeed.StartupMilestone` event for Mod construction, title initialization, the first operable title tick, first rendered client-world frame and server startup. Every event carries process-relative milliseconds and the process start epoch, keeping profiler and ordinary-run boundaries comparable.

## Compatibility and rollback

Every bootstrap bytecode patch requires an exact SHA-256 target fingerprint. Unknown versions log `unsupported fingerprint` and retain the original code.

The distribution also requires `lightspeed.refmap.json`. Gradle declares the generated refmap as a `compileJava` output, so a missing incremental artifact invalidates the compile task and regenerates mappings before `jarJar`; `verifyDistribution` rejects any final JAR without the refmap.

The installed Agent also receives `-Dlightspeed.agent.owner=<mod-jar>`. At premain it verifies that the owner still embeds the exact same Agent SHA-256. If the Mod is removed, replaced or updated in place, a stale extracted Agent becomes inert; the new Mod reconciles the profile on its next ordinary launch.

To undo automatic launcher configuration, remove the three Agent arguments and, when present, the three managed budget arguments from the current profile:

```text
-Dlightspeed.bootstrapCacheDir=...
-Dlightspeed.agent.owner=...
-javaagent:...lightspeed-bootstrap-agent-....jar
-Dlightspeed.workers=...
-Dlightspeed.managedCICompilerCount=...
-XX:CICompilerCount=...
```

The compiler-count marker owns only the immediately following matching `-XX:CICompilerCount` token, so a separate user override is preserved. Switching to GraalVM removes the managed budget on the next profile reconciliation; switching back to a supported HotSpot runtime reinstalls a hardware-scaled budget.

Uninstall order matters: first remove all Lightspeed-owned arguments and verify that the next launch no longer references the Agent, then delete `.lightspeed/`. The ordinary `lightspeed-cache` directory can be deleted independently because it does not contain the Agent executable. No save data is stored in either directory.

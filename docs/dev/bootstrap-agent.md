# Lightspeed Bootstrap Agent

Lightspeed 1.2.4 produces two independent artifacts:

- `lightspeed-<version>.jar` is the ordinary Forge/NeoForge mod and remains usable by itself.
- `lightspeed-bootstrap-agent-<version>.jar` is the optional early-startup accelerator.

The Agent runs from JVM `premain`, before `BootstrapLauncher.main`. It does not replace the Minecraft JAR or any Forge/NeoForge library. Remove its single JVM argument to roll back.

## Installation

1. Install the ordinary Lightspeed mod JAR in the instance `mods` directory.
2. Keep the bootstrap Agent JAR outside `mods`.
3. Add this JVM argument to the instance:

   ```text
   -javaagent:C:\absolute\path\to\lightspeed-bootstrap-agent-<version>.jar
   ```

Paths containing spaces follow the launcher's normal JVM-argument quoting rules. Do not pass the Agent JAR as `-jar`, place it in `mods`, or replace a Forge/NeoForge library with it.

For this repository's Forge development run:

```powershell
.\gradlew.bat runClient -Plightspeed.bootstrapAgent=true
```

Without the property, `runClient` starts the ordinary mod without the Agent.

## Supported startup libraries

The bytecode transformer is fail-closed and matches the complete SHA-256 of each target class before applying a patch.

| Runtime | Target |
|---|---|
| Forge 1.20.1 production | FML Loader 47.4.0, ModLauncher 10.0.9, SecureJarHandler 2.1.10, ForgeSPI 7.0.1 |
| Forge 1.20.1 development | FML Loader 47.4.20, ModLauncher 10.0.9, SecureJarHandler 2.1.10, ForgeSPI 7.0.1 |
| NeoForge 1.21.1 | FML 4.0.42, ModLauncher 11.0.5, SecureJarHandler 3.0.8 |

An unknown class fingerprint is logged and left unchanged. A partial transform is never installed.

## Optimizations

### SERVICE-layer fast negative

Before FML constructs a complete `SecureJar`/`JarContents`, the Agent checks the physical ZIP central directory for the exact startup-service files. Explicit or versioned `module-info.class`, multi-release JARs, directories, unreadable archives, and unsupported files all fall back to the original loader path. This fast path can reject only a JAR that cannot provide a recognized startup service.

### Shared immutable-JAR resource index

When SecureJar creates an immutable UnionFS root, the Agent records all physical JAR roots and the active path filter. It reads each ZIP central directory once, publishes one exact sorted entry table plus a Bloom front filter, and shares immutable integer handles with the ordinary Mod through a fail-open bootstrap bridge.

Each handle is bound to the pack's exact logical root. Standard packs and safe sub-path packs therefore see only their own relative entries. Directory-to-range tables and namespace sets are built once; hot listings no longer perform a filesystem map lookup, `CompletableFuture.join`, reflective method invocation, binary string search, or result-array copy. Mutable directories, multi-release overlays, unsupported file systems, and index failures retain the original path.

Index construction is `O(E log E)` once per JAR root, where `E` is the visible entry count. Exact membership is `O(log E)` after four Bloom probes, while a bound prefix listing is `O(1 + K)` for `K` returned resources. With the Agent active, the Mod does not eagerly load or rebuild its legacy serialized resource-list caches.

### EventBus declaration cache

Forge EventBus 6.0.5 repeatedly resolves inherited public listener methods with `Class.getDeclaredMethod` while registering objects. The Agent replaces only that private lookup helper with a `ClassValue`-scoped concurrent cache. Present and absent results are both retained, while annotation checks, listener ordering, factory generation, and registration remain in Forge's original code.

### Resource-byte startup image

For exact immutable Mod JAR pack views, successful resource opens are captured after the original UnionFS path has proved the resource exists. At the title screen, newly observed bytes are atomically merged into `lightspeed-cache/bootstrap/resource-image-v2.bin`. Later launches validate the Java/classpath/module-path/Mod-JAR metadata fingerprint and load the image asynchronously while Forge constructs mods.

Image hits return an in-memory stream before UnionFS or ZIP access. Unregistered or mutable packs, multi-release roots, stale fingerprints, failed reads, entries larger than 32 MiB, and data beyond the default 512 MiB image budget keep the original path. Exact immutable sub-path views are supported. The budget can be changed with `-Dlightspeed.resourceImageMiB=<64..1024>`. Deleting the image is a complete rollback.

### Raw class-byte image

The Agent patches SecureJarHandler's `ModuleClassLoader.getClassBytes` and records only the immutable bytes read before ModLauncher transformation. A hit bypasses the corresponding UnionFS/ZIP read, then follows the original Mixin, AccessTransformer, transformer, signer, protection-domain, verifier, and `defineClass` path unchanged. The class image is independent from resource bytes and defaults to 64 MiB (`-Dlightspeed.classImageMiB=<16..256>`). Set `-Dlightspeed.rawClassImage=false` to disable it.

### Forge scan-metadata image

Forge 47.4 scans class files with ASM to construct `ModFileScanData`. The Agent stores a neutral, bounded representation of each class/annotation record and reconstructs the same ForgeSPI record types on a cache hit. Language-loader visitors still run after the core scan. The image defaults to 128 MiB (`-Dlightspeed.scanImageMiB=<16..512>`); set `-Dlightspeed.scanMetadataCache=false` to disable it. Unsupported values, stale fingerprints, module-access failures, and corrupt entries fall back to Forge's original scanner.

### Rejected transformed-bytecode cache

An explicit two-run ATM9 experiment always executed the real transformation chain and compared outputs for the same class name, context, and raw-byte SHA-256. It observed 49,092 matches and 926 mismatches. ModLauncher also mutates its audit trail and invokes arbitrary plugin/transformer callbacks. Returning cached transformed bytes would therefore change observable loader state and is intentionally not implemented.

## Diagnostics and rollback

Expected early log lines include:

```text
[Lightspeed Agent] active: ...
[Lightspeed Agent] patched net/minecraftforge/fml/loading/ModDirTransformerDiscoverer ...
[Lightspeed Agent] patched cpw/mods/jarhandling/impl/Jar ...
[Lightspeed Agent] patched cpw/mods/cl/ModuleClassLoader ...
[Lightspeed Agent] patched net/minecraftforge/fml/loading/moddiscovery/Scanner ...
[Lightspeed Agent] patched net/minecraftforge/eventbus/EventBus ...
```

At JVM shutdown, a summary reports SERVICE candidates/rejections, resource queries/rejections, indexed roots/entries, and fail-open failures. If a supported target logs `unsupported fingerprint`, remove `-javaagent` until that loader build is explicitly validated.

Rollback is only:

1. Remove the `-javaagent:...` JVM argument.
2. Start the same instance again.

No production version JSON edit or save change is involved. The optional `resource-image-v2.bin`, `class-image-v1.bin`, and `scan-image-v1.bin` files can be deleted independently.

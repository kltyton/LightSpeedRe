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

For either branch's development run:

```powershell
.\gradlew.bat runClient -Plightspeed.bootstrapAgent=true
```

Without the property, `runClient` starts the ordinary mod without the Agent.

## Supported startup libraries

The bytecode transformer is fail-closed and matches the complete SHA-256 of each target class before applying a patch.

| Runtime | Target |
|---|---|
| Forge 1.20.1 production | FML Loader 47.4.0, ModLauncher 10.0.9, SecureJarHandler 2.1.10 |
| Forge 1.20.1 development | FML Loader 47.4.20, SecureJarHandler 2.1.10 |
| NeoForge 1.21.1 | FML 4.0.42, ModLauncher 11.0.5, SecureJarHandler 3.0.8 |

An unknown class fingerprint is logged and left unchanged. A partial transform is never installed.

## Optimizations

### SERVICE-layer fast negative

Before FML constructs a complete `SecureJar`/`JarContents`, the Agent checks the physical ZIP central directory for the exact startup-service files. Explicit or versioned `module-info.class`, multi-release JARs, directories, unreadable archives, and unsupported files all fall back to the original loader path. This fast path can reject only a JAR that cannot provide a recognized startup service.

### Immutable-JAR resource negative index

For each SecureJar UnionFS root, the Agent builds one Bloom membership index from the filtered root view. Definite misses skip `Files.exists` and `UnionFileSystem.testFilter`; possible hits always execute the original lookup. Mutable directories, multi-release overlays, unsupported file systems, and index failures fall back to the original path.

Index construction is `O(E)` once per JAR root, where `E` is the visible entry count. Queries are `O(1)` with four bit probes. The bitset targets 16 bits per entry, with a 1 Ki-bit minimum and 128 Mi-bit maximum; saturation increases false positives but cannot hide an existing resource.

## Diagnostics and rollback

Expected early log lines include:

```text
[Lightspeed Agent] active: ...
[Lightspeed Agent] patched net/minecraftforge/fml/loading/ModDirTransformerDiscoverer ...
[Lightspeed Agent] patched cpw/mods/jarhandling/impl/Jar ...
```

At JVM shutdown, a summary reports SERVICE candidates/rejections, resource queries/rejections, indexed roots/entries, and fail-open failures. If a supported target logs `unsupported fingerprint`, remove `-javaagent` until that loader build is explicitly validated.

Rollback is only:

1. Remove the `-javaagent:...` JVM argument.
2. Start the same instance again.

No cache migration, production version JSON edit, or save change is involved.

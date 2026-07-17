# Lightspeed Startup Architecture

## Target

Lightspeed is a NeoForge 1.21.1 startup optimization mod. It keeps the original
resource cache strategy, then adds bounded multi-worker execution for cache I/O.

## Build Baseline

- Minecraft: 1.21.1
- NeoForge: 21.1.234
- Java toolchain: 21
- Gradle wrapper: 8.14.5

## Optimization Paths

- Persistent resource-existence and namespace caches are loaded early from
  `<game directory>/lightspeed-cache/<minecraft version>/`.
- Cache loading, cache deletion, and cache persistence use a fixed worker pool.
- `net.minecraft.server.packs.PathPackResources#listResources` caches walked
  file paths per pack type and namespace, replacing repeated full directory
  walks during resource discovery.
- Resource-list indexes are persisted under
  `<game directory>/lightspeed-cache/<minecraft version>/resourceLists/`.
  Cold starts build those indexes on worker threads; warm starts load them from
  disk before pack scanning begins.
- `FallbackResourceManager#getResource` uses segmented parallel lookup only for
  contiguous unfiltered vanilla `PathPackResources` / `FilePackResources`
  entries. Unknown, dynamic, Fusion-overridden, or filtered packs keep vanilla
  sequential lookup order.
- Blockstate/model hot paths still use the original hash, material, predicate,
  dependency, and string-split caches.

The worker count defaults to the local CPU count capped at 32, with a minimum
of 2. Override it with:

```text
-Dlightspeed.workers=<positive integer>
```

## NeoForge Common Config

`config/lightspeed-common.toml` controls the risky startup optimizations:

- `startup.asyncPreloadPacks=true`: background mod resource-pack scanning is
  enabled by default and builds resource-list indexes concurrently.
- `startup.parallelResourceLookup=true`: concurrent candidate-pack lookup in
  `FallbackResourceManager#getResource` is enabled only for safe unfiltered pack
  segments while preserving vanilla priority and filter order.
- `compatibility.isolateModdedResourceReloadFailures=true`: third-party client
  resource reload listener failures are logged and isolated so one broken mod
  listener does not fail the entire loading overlay.
- `compatibility.isolatedResourceReloadListenerPatterns=["*"]`: controls which
  non-core listener or renderer class names may be isolated. `*` means any code
  outside Minecraft, NeoForge, Mojang, LightSpeed, and framework packages.

The old JVM properties are not required for these switches anymore.

## Compatibility Switches

- `sophisticatedstorage` + `jsonthings`: disables walked-path caching.
- `multiblocked`: disables material caching.
- Fusion resource-pack overrides: when a pack has Fusion's override folder
  enabled, Lightspeed leaves namespace and resource listing to the original pack
  implementation so Fusion can add override resources and filter replaced files.

These switches preserve known compatibility behavior from the 1.19.2 source
tree while keeping the rest of the startup optimization active.

## Validation

Use the repository wrapper:

```bat
.\gradlew.bat runData
.\gradlew.bat build
.\gradlew.bat runClient
```

`runClient` is the decisive check for mixin target validity because some
targets are loader classes and are not fully validated by Java compilation.

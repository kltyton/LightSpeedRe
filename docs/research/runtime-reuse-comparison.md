# JVM Shared Classes vs. Process Snapshots

Date: 2026-08-21

## Pure performance conclusion

For the target Forge pack, a sufficiently late valid checkpoint on fast local storage has a higher warm-restore performance ceiling than shared classes alone.

JVM shared-class mechanisms reuse class metadata and may reuse AOT/JIT products, but they still create a new JVM and execute Forge discovery not covered by the cache, Mod construction, static initialization, registration and client resource loading. A process snapshot can restore the heap, already defined classes, initialized Mod objects and compiled code from a coordinated checkpoint, so it can skip a strictly larger portion of startup.

Under those conditions, the expected ordering for pure warm-restore latency is:

```text
valid process snapshot > shared classes > ordinary cold JVM startup
```

This is not an unconditional law or a portability recommendation. Snapshot position, image size, storage latency, page faults and work remaining after restore can change the measured result. A Minecraft client snapshot must also recover or recreate GLFW windows, OpenGL contexts, OpenAL devices, file watchers, native handles and network state. A snapshot taken before native client initialization avoids some of those resources but leaves more resource startup after restore.

## Combining both

Combining shared classes and process snapshots does not add their percentage improvements. A restored snapshot already contains the defined classes and initialized heap, so shared classes contribute little to the successful restore hot path. They can still improve:

- the cold launch that creates a new snapshot;
- fallback launches after a snapshot is invalidated;
- classes loaded after the checkpoint and additional JVMs sharing the same class cache;
- environments where checkpoint/restore is unavailable.

Thus the combination is better for fallback coverage and amortized availability, not necessarily for the minimum single warm-restore time. It also adds two invalidation systems and more persistent storage.

## Current Forge boundary

ModLauncher 10.0.9 explicitly rejects OpenJ9 before constructing its launcher because its transformation behavior is unsupported. HotSpot JEP 483 does not target classes loaded by user-defined class loaders, which excludes the Forge GAME `ModuleClassLoader` population measured in this repository. Java 17 AppCDS has separate custom-loader facilities and must not be conflated with JEP 483; its transformed Forge coverage still requires an independent measurement.

The user separately verified that OpenJ9 shared classes did not approach the 30-second target for the target pack. Lightspeed therefore keeps JVM replacement outside the product path and focuses its portable implementation on loader work reduction, exact caches and the optional embedded premain Agent.

## Sources

- OpenJ9 shared classes and custom loaders: https://eclipse.dev/openj9/docs/shrc/
- OpenJ9 `-Xshareclasses` and bytecode instrumentation behavior: https://eclipse.dev/openj9/docs/xshareclasses/
- OpenJDK AOT cache boundary: https://openjdk.org/jeps/483
- OpenJDK CRaC project: https://openjdk.org/projects/crac/

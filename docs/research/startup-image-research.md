# 1.20.1 Startup Image Research

Date: 2026-08-21

## Scope

This record covers external implementation patterns considered for the Forge 1.20.1 startup work. The implementation remains clean-room and does not copy third-party source.

## Sources

### DashLoader and Forge port

- Repositories: `alphaqu/DashLoader` and `Hypocisy/dashloader` (`forge-1.20.1`, inspected commit `28bf61760f0467b8c47b6edc952dd0b589d8879c`).
- Relevant design: a first launch captures Minecraft asset products, later launches restore a cache selected by the active content hash; unsupported asset implementations fall back to normal loading.
- Relevant boundaries: model, sprite-stitch, shader, font, and splash modules are handled separately instead of assuming arbitrary runtime objects are serializable.
- License result: no `LICENSE`, `LICENSE.md`, or `COPYING` file was present in the inspected upstream or Forge-port roots. Treat the code as unavailable for copying. Only the high-level cache/fallback architecture informed this project.
- URLs:
  - https://github.com/alphaqu/DashLoader
  - https://github.com/Hypocisy/dashloader

### ModernFix

- Repository: `embeddedt/ModernFix`, branch `1.20`, inspected commit `7590629ab595d62bfb94f23121562aaada7d7708`.
- License: LGPL-3.0-or-later.
- Relevant design: model JSON and blockstate loading are made lazy, large texture atlases use a specialized STB-backed stitcher, and incompatible loading states fall back to vanilla behavior.
- ATM9 already contains ModernFix, so Lightspeed must not duplicate or replace those object-level optimizations.
- URL: https://github.com/embeddedt/ModernFix

### OpenJDK AOT and CRaC

- JEP 483 AOT class loading/linking does not cache classes loaded by user-defined class loaders, which excludes Forge's `ModuleClassLoader` hot path.
- Java 17 static AppCDS is a separate mechanism. OpenJDK's tests demonstrate fingerprint-mode custom-loader archiving, but ATM9's transformed Mod classes were absent from the normal class list. Enabling the diagnostic Java-Agent archive mode changed Forge behavior and caused Quark/Supplementaries FATAL failures, so this route was rejected.
- CRaC currently relies on Linux/CRIU and requires open files, sockets, audio, and graphics resources to participate in checkpoint/restore. It is not a Windows Minecraft delivery path here.
- URLs:
  - https://openjdk.org/jeps/483
  - https://github.com/openjdk/jdk/tree/master/test/hotspot/jtreg/runtime/cds/appcds/customLoader
  - https://github.com/openjdk/crac

## Selected clean-room design

Lightspeed caches immutable resource bytes, bounded raw pre-transform class bytes, and neutral Forge scan metadata that were observed during a real startup. It does not serialize baked models, mod objects, GL handles, native images, loaded `Class` objects, or transformed bytecode.

- First run: read through the original UnionFS/Forge paths, retain bounded data by category, and atomically write independent images after the title screen appears.
- Later runs: validate Java, classpath, module-path, and complete Mod-JAR metadata fingerprints before serving cached data.
- Dynamic packs, resource packs without an `IModFile`, directory roots, multi-release roots, oversized resources, stale images, and any read/write failure use the original path.
- The image has fixed per-entry and total-size limits. Removing it is a complete rollback.

The production images are independently bounded at 512 MiB for resources, 64 MiB for raw classes, and 128 MiB for scan metadata. Splitting them prevents class bytes from evicting the resource workload. A combined 447 MB prototype was rejected after 52-54 second warm starts; the split logical-root design reached 47 seconds on capture and 46 seconds warm.

The transformed-byte experiment remained read-only with respect to loader behavior: it executed all transformers and compared output. The second run observed 49,092 identical outputs and 926 mismatches for identical input keys. Together with ModLauncher's audit/plugin side effects, this rejects transformed-byte short-circuit caching as a generic optimization.

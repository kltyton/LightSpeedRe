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

Lightspeed caches only immutable standard-Mod resource bytes that were successfully opened during a real startup. It does not serialize baked models, mod objects, GL handles, native images, classes, or transformed bytecode.

- First run: read through the original UnionFS path, retain bounded bytes, and atomically write one startup image after the title screen appears.
- Later runs: validate the complete Mod-JAR metadata fingerprint and serve matching bytes before UnionFS/ZIP access.
- Dynamic packs, resource packs without an `IModFile`, directory roots, multi-release roots, oversized resources, stale images, and any read/write failure use the original path.
- The image has fixed per-entry and total-size limits. Removing it is a complete rollback.

This targets generic ZIP/UnionFS resource I/O while preserving ModernFix and third-party model/render behavior.

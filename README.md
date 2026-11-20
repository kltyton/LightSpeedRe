# LightSpeedRe

[English](README.md) | [简体中文](README.zh-CN.md)

LightSpeedRe is a client-side Minecraft mod that reduces startup work for modpacks. It reuses data from previous launches and runs resource preparation in parallel where the loader allows it. The title screen shows the time from JVM startup to the usable menu.

## Installation

Download the JAR for your Minecraft version and loader, then place it in the instance's `mods` folder. Fabric builds also require Fabric API for the same Minecraft version. [Modrinth](https://modrinth.com/mod/lightspeedre) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lightspeedre-launch-optimizations)

| Minecraft | Loaders | Java |
| --- | --- | --- |
| 1.20.1 | Forge, Fabric | 17 |
| 1.21.1 | NeoForge, Fabric | 21 |
| 26.1.2, 26.2 | NeoForge, Fabric | 25 |

The base mod runs without extra JVM arguments. The optional Bootstrap Agent is available on Forge 1.20.1 and NeoForge 1.21.1. In PCL and Prism, LightSpeedRe 2.0.7 prepares the local Agent only when requested and gives you game-directory-relative arguments to copy. Each player can set it up in their own instance.

## Fix a Lightspeed Agent startup error

If Java stops with **Error opening zip file or JAR manifest missing** or **agent library failed Agent_OnLoad** and names `lightspeed-bootstrap-agent-*.jar`, clear the old arguments before starting Minecraft.

| Launcher | Where to edit |
| --- | --- |
| PCL2 (Windows) | Version Settings → Settings → Advanced Launch Options → JVM Arguments |
| Prism Launcher (Windows, Linux, macOS) | Edit Instance → Settings → Java → Java arguments |

1. Remove the complete `-javaagent:…/lightspeed-bootstrap-agent-*.jar` argument, including surrounding quotes. Also remove any associated `-Dlightspeed.agent.owner=…` and `-Dlightspeed.bootstrapCacheDir=…` entries. Leave `-Xmx`, `-Xms`, and other mods' agents in place. If the instance inherits its Java settings, check the launcher's global Java arguments too.
2. Clear the Lightspeed pre-launch command if it points to `.lightspeed/bootstrap/pack-compiler.cmd` or `pack-compiler.sh`. In Prism this is under Settings → Custom Commands. In PCL2 it is under Advanced Launch Options.
3. Update LightSpeedRe. With an older build, set `installBootstrapAgent = false` in the instance's `config/lightspeed-common.toml` to stop it from writing the arguments back. Save your changes, fully close the launcher, then reopen it and start the instance.

If the launcher settings still contain stale entries, download the [repair script](common/src/main/resources/META-INF/lightspeed/repair-launcher.ps1). Close the launcher and run `pwsh -NoProfile -File ./repair-launcher.ps1 -GameDirectory "<instance game folder>"` from the directory where you saved the script. Use the game folder that contains `mods`. In Prism it is usually the `.minecraft` or `minecraft` folder inside the instance. PowerShell 7 is required for this command.

**Modpack authors** should remove these local Agent settings from the exported profile before sharing it. Recipients can run the mod with the base settings and set up their own Agent later.

## License and credits

LightSpeedRe is maintained by kltyton and builds on [Lightspeed by CCr4ft3r](https://github.com/CCr4ft3r/lightspeed). It is licensed under [LGPL-3.0-or-later](LICENSE).

Report issues with the Minecraft version, loader, launcher, and the relevant startup log. [Issue tracker](https://github.com/kltyton/LightSpeedRe/issues)

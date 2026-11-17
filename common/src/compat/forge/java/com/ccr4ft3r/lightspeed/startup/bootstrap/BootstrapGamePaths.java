package com.ccr4ft3r.lightspeed.startup.bootstrap;

import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Path;

final class BootstrapGamePaths {
    private BootstrapGamePaths() { }

    static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("lightspeed-common.toml");
    }
}

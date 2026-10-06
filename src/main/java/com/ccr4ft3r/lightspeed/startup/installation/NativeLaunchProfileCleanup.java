package com.ccr4ft3r.lightspeed.startup.installation;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

/** Removes the saved bootstrap route without changing ordinary Mod worker settings. */
public final class NativeLaunchProfileCleanup {
    private static final Logger LOGGER = LogUtils.getLogger();

    private NativeLaunchProfileCleanup() { }

    public static void removeBootstrapArguments(Path gameDirectory) {
        try {
            LaunchProfileInstaller.Result result = LaunchProfileInstaller.installWithPreLaunch(
                    gameDirectory.toAbsolutePath().normalize(), List.of(), "");
            if (result.changed()) {
                LOGGER.info("Lightspeed removed its saved bootstrap Agent and managed compiler arguments from {}. Current JVM arguments are unchanged; the next launch uses the updated profile.",
                        result.description());
            }
        } catch (Exception exception) {
            LOGGER.warn("Lightspeed could not remove its saved bootstrap arguments from the current launcher profile",
                    exception);
        }
    }
}

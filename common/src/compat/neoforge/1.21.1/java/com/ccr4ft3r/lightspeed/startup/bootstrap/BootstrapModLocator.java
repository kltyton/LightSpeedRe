package com.ccr4ft3r.lightspeed.startup.bootstrap;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/** Gives the already located SERVICE JAR to the ordinary game-mod readers. */
public final class BootstrapModLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        pipeline.addJarContent(JarContents.of(BootstrapLocation.owner()),
                ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
    }
}

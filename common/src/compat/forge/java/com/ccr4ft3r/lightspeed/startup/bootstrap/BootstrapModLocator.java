package com.ccr4ft3r.lightspeed.startup.bootstrap;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/** Registers the service JAR through Forge's ordinary mod validation and loading pipeline. */
public final class BootstrapModLocator extends AbstractJarFileModLocator {
    @Override
    public String name() {
        return "lightspeed";
    }

    @Override
    public Stream<Path> scanCandidates() {
        return Stream.of(BootstrapLocation.owner());
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        // This locator has no launcher-specific arguments.
    }
}

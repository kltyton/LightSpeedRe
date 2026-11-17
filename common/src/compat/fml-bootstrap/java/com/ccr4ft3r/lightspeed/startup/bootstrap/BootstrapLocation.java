package com.ccr4ft3r.lightspeed.startup.bootstrap;

import cpw.mods.niofs.union.UnionFileSystem;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BootstrapLocation {
    private BootstrapLocation() { }

    public static Path owner() {
        try {
            Path source = Path.of(BootstrapLocation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (source.getFileSystem() instanceof UnionFileSystem union) source = union.getPrimaryPath();
            source = source.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("Early bootstrap requires a packaged Lightspeed JAR: " + source);
            }
            return source;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cannot resolve the Lightspeed service JAR", exception);
        }
    }
}

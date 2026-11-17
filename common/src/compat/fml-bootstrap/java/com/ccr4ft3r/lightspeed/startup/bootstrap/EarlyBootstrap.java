package com.ccr4ft3r.lightspeed.startup.bootstrap;

import com.ccr4ft3r.lightspeed.startup.bootstrap.index.EarlyZipResourceIndex;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.slf4j.Logger;

public final class EarlyBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EarlyBootstrap() { }

    public static void start(Path gameDirectory, Path configFile) throws Exception {
        Path instance = gameDirectory.toAbsolutePath().normalize();
        Path runtime = instance.resolve(".lightspeed/bootstrap/runtime");
        boolean agentActive = Boolean.getBoolean("lightspeed.bootstrapAgent.active");
        try {
            removePayloads(instance, runtime);
        } catch (IOException exception) {
            LOGGER.warn("Failed to clean legacy Lightspeed bootstrap runtime directory {}", runtime, exception);
        }
        EarlyZipResourceIndex.preindexInstance(instance);
        LOGGER.info(agentActive
                ? "Lightspeed early bootstrap initialized with JVM bootstrap agent active"
                : "Lightspeed early bootstrap initialized in loader-native mode (no JVM agent)");
    }

    private static void removePayloads(Path instance, Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!directory.toRealPath().startsWith(instance.toRealPath())) {
            throw new IOException("The bootstrap runtime directory resolves outside the current instance: " + directory);
        }
        try (var files = Files.list(directory)) {
            for (Path version : files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}")).toList()) {
                if (!Files.isDirectory(version, LinkOption.NOFOLLOW_LINKS)) continue;
                Path file = version.resolve("lightspeed-bootstrap-agent.jar");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file);
                Files.delete(version);
            }
        }
    }
}

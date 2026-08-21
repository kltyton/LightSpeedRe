package com.ccr4ft3r.lightspeed.startup.installation;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.cache.persistence.AtomicFileWriter;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class BootstrapAgentInstaller {
    public static final String ACTIVE_PROPERTY = "lightspeed.bootstrapAgent.active";
    public static final String DIGEST_PROPERTY = "lightspeed.bootstrapAgent.digest";
    public static final String OWNER_PROPERTY = "lightspeed.agent.owner";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EMBEDDED_AGENT = "/META-INF/lightspeed/bootstrap-agent.jar";

    private BootstrapAgentInstaller() {
    }

    public static void installForNextLaunch() {
        try {
            Path owner = locateOwningMod();
            if (owner == null) {
                LOGGER.debug("Lightspeed embedded bootstrap installation skipped outside a packaged Mod JAR");
                return;
            }

            byte[] agentBytes = readEmbeddedAgent();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(agentBytes));
            if (Boolean.getBoolean(ACTIVE_PROPERTY) && digest.equals(System.getProperty(DIGEST_PROPERTY))) {
                return;
            }
            Path agent = FMLPaths.GAMEDIR.get()
                    .resolve(".lightspeed")
                    .resolve("bootstrap")
                    .resolve("lightspeed-bootstrap-agent-" + digest.substring(0, 16) + ".jar")
                    .toAbsolutePath()
                    .normalize();
            if (!matches(agent, agentBytes)) {
                AtomicFileWriter.write(agent, agentBytes);
            }

            LaunchProfileInstaller.Result result = LaunchProfileInstaller.install(
                    FMLPaths.GAMEDIR.get().toAbsolutePath().normalize(), owner, agent);
            if (result.changed()) {
                LOGGER.info("Lightspeed installed its embedded bootstrap for the next launch via {}", result.description());
            } else if (result == LaunchProfileInstaller.Result.UNSUPPORTED) {
                LOGGER.warn("Lightspeed extracted its embedded bootstrap to {}, but this launcher profile could not be updated automatically. Add JVM arguments {}, {}, and {}",
                        agent, cacheDirectoryArgument(FMLPaths.GAMEDIR.get()), ownerArgument(owner), agentArgument(agent));
            }
        } catch (Exception exception) {
            LOGGER.warn("Lightspeed could not install its embedded bootstrap; the ordinary Mod optimizations remain active",
                    exception);
        }
    }

    static String ownerArgument(Path owner) {
        return "-D" + OWNER_PROPERTY + '=' + owner;
    }

    static String agentArgument(Path agent) {
        return "-javaagent:" + agent;
    }

    static String cacheDirectoryArgument(Path gameDirectory) {
        return "-Dlightspeed.bootstrapCacheDir=" + gameDirectory.resolve("lightspeed-cache")
                .resolve("bootstrap").toAbsolutePath().normalize();
    }

    private static Path locateOwningMod() {
        try {
            var modFile = ModList.get().getModFileById(ModConstants.MOD_ID);
            if (modFile == null) {
                return null;
            }
            Path path = modFile.getFile().getFilePath().toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static byte[] readEmbeddedAgent() throws Exception {
        try (InputStream stream = BootstrapAgentInstaller.class.getResourceAsStream(EMBEDDED_AGENT)) {
            if (stream == null) {
                throw new IllegalStateException("Embedded bootstrap Agent is missing from the Mod JAR");
            }
            return stream.readAllBytes();
        }
    }

    private static boolean matches(Path path, byte[] expected) throws Exception {
        return Files.isRegularFile(path)
                && Files.size(path) == expected.length
                && MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),
                MessageDigest.getInstance("SHA-256").digest(expected));
    }
}

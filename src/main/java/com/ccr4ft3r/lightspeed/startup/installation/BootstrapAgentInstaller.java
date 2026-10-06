package com.ccr4ft3r.lightspeed.startup.installation;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.config.LightspeedConfig;
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
import java.util.ArrayList;
import java.util.List;

public final class BootstrapAgentInstaller {
    public static final String ACTIVE_PROPERTY = "lightspeed.bootstrapAgent.active";
    public static final String DIGEST_PROPERTY = "lightspeed.bootstrapAgent.digest";
    public static final String DYNAMIC_PROPERTY = "lightspeed.bootstrapAgent.dynamic";
    public static final String OWNER_PROPERTY = "lightspeed.agent.owner";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EMBEDDED_AGENT = "/META-INF/lightspeed/bootstrap-agent.jar";
    private static final java.util.concurrent.atomic.AtomicBoolean PACK_COMPILER_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile InstallationState installationState = InstallationState.NOT_ATTEMPTED;
    private static volatile PackCompilerCommand.Prepared packCompiler;

    private BootstrapAgentInstaller() {
    }

    public static void installForNextLaunch() {
        installationState = InstallationState.IN_PROGRESS;
        try {
            Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            if (!LightspeedConfig.COMMON.installBootstrapAgent.get()) {
                LaunchProfileInstaller.Result result = LaunchProfileInstaller.installWithPreLaunch(
                        gameDirectory, List.of(), "");
                installationState = InstallationState.CONFIGURED;
                if (result.changed()) {
                    LOGGER.info("Lightspeed removed its saved startup arguments from {}. Reopen the launcher before the next launch if it still uses old arguments.", result.description());
                }
                return;
            }
            PreparedAgent prepared = prepareEmbeddedAgent();
            if (prepared == null) {
                installationState = InstallationState.MANUAL_REQUIRED;
                LOGGER.debug("Lightspeed embedded bootstrap installation skipped outside a packaged Mod JAR");
                return;
            }

            List<String> arguments = launchArguments(gameDirectory, prepared);
            String preLaunchCommand = null;
            try {
                packCompiler = PackCompilerCommand.prepare(
                        gameDirectory, prepared.agent(), prepared.owner(), prepared.digest());
                preLaunchCommand = packCompiler.invocation();
            } catch (Exception exception) {
                LOGGER.warn("Lightspeed Pack Compiler pre-launch setup is unavailable; runtime scan caching remains active",
                        exception);
            }
            LaunchProfileInstaller.Result result = LaunchProfileInstaller.installWithPreLaunch(
                    gameDirectory, arguments, preLaunchCommand);
            if (result.changed()) {
                installationState = InstallationState.CONFIGURED;
                LOGGER.info("Lightspeed installed the embedded bootstrap Agent for the next launch via {}",
                        result.description());
            } else if (result == LaunchProfileInstaller.Result.UNSUPPORTED) {
                installationState = InstallationState.MANUAL_REQUIRED;
                LOGGER.warn("Lightspeed prepared the embedded bootstrap Agent, but this launcher profile was not updated automatically. If desired, add JVM arguments {}",
                        arguments);
            } else {
                installationState = InstallationState.CONFIGURED;
            }
            LauncherRefreshCoordinator.refreshAfterInstall(result);
        } catch (Exception exception) {
            installationState = InstallationState.MANUAL_REQUIRED;
            LOGGER.warn("Lightspeed was unable to install its embedded bootstrap automatically; the ordinary Mod optimizations remain active",
                    exception);
        }
    }

    public static boolean manualConfigurationRequired() {
        return LightspeedConfig.COMMON.installBootstrapAgent.get()
                && installationState != InstallationState.CONFIGURED;
    }

    public static boolean launcherReadyForNextLaunch() {
        return !LightspeedConfig.COMMON.installBootstrapAgent.get()
                || LauncherRefreshCoordinator.readyForNextLaunch();
    }

    public static void startPackCompilerAfterTitle() {
        PackCompilerCommand.Prepared prepared = packCompiler;
        if (prepared == null || Boolean.getBoolean(ACTIVE_PROPERTY)
                || !PACK_COMPILER_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Path log = gameDirectory.resolve("lightspeed-cache").resolve("pack-compiler.log");
            Files.createDirectories(log.getParent());
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            ProcessBuilder builder = new ProcessBuilder(windows
                    ? List.of("cmd.exe", "/d", "/s", "/c", "call", prepared.script().toString())
                    : List.of("/bin/sh", prepared.script().toString()));
            builder.directory(gameDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Process process = builder.start();
            Thread monitor = new Thread(() -> awaitPackCompiler(process),
                    "Lightspeed-Pack-Compiler-Monitor");
            monitor.setDaemon(true);
            monitor.setPriority(Thread.MIN_PRIORITY);
            monitor.start();
        } catch (Exception exception) {
            LOGGER.warn("Lightspeed could not prime the next-launch scan image; Forge runtime scanning remains active",
                    exception);
        }
    }

    public static String manualJvmArguments() {
        try {
            PreparedAgent prepared = prepareEmbeddedAgent();
            if (prepared == null) {
                return "";
            }
            Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            return LaunchProfileInstaller.formatArgumentLine(launchArguments(gameDirectory, prepared));
        } catch (Exception exception) {
            LOGGER.warn("Lightspeed manual bootstrap Agent arguments are currently unavailable", exception);
            return "";
        }
    }

    public static PreparedAgent prepareEmbeddedAgent() throws Exception {
        Path owner = locateOwningMod();
        if (owner == null) {
            return null;
        }
        byte[] agentBytes = readEmbeddedAgent();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(agentBytes));
        Path agent = FMLPaths.GAMEDIR.get()
                .resolve(".lightspeed")
                .resolve("bootstrap")
                .resolve("lightspeed-bootstrap-agent-" + digest.substring(0, 16) + ".jar")
                .toAbsolutePath()
                .normalize();
        if (!matches(agent, agentBytes)) {
            AtomicFileWriter.write(agent, agentBytes);
        }
        return new PreparedAgent(owner, agent, digest);
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

    private static List<String> launchArguments(Path gameDirectory, PreparedAgent prepared) {
        List<String> arguments = new ArrayList<>();
        arguments.add(cacheDirectoryArgument(gameDirectory));
        arguments.add(ownerArgument(prepared.owner()));
        arguments.add(agentArgument(prepared.agent()));
        arguments.addAll(StartupThreadBudget.arguments());
        return List.copyOf(arguments);
    }

    static Path locateOwningMod() {
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

    private static void awaitPackCompiler(Process process) {
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOGGER.warn("Lightspeed background Pack Compiler exited with code {}", exitCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record PreparedAgent(Path owner, Path agent, String digest) {
    }

    private enum InstallationState {
        NOT_ATTEMPTED,
        IN_PROGRESS,
        CONFIGURED,
        MANUAL_REQUIRED
    }
}

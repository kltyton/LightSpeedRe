package com.ccr4ft3r.lightspeed.bootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;

public final class LightspeedAgent {
    private static final Set<String> TARGET_CLASSES = Set.of(
            "net.minecraftforge.fml.loading.ModDirTransformerDiscoverer",
            "net.neoforged.fml.loading.ModDirTransformerDiscoverer",
            "cpw.mods.jarhandling.impl.Jar",
            "cpw.mods.cl.ModuleClassLoader",
            "cpw.mods.modlauncher.ModuleLayerHandler",
            "net.minecraftforge.fml.loading.moddiscovery.Scanner",
            "net.minecraftforge.eventbus.EventBus",
            "net.minecraftforge.eventbus.ModLauncherFactory");
    private static JarFile bootstrapJar;

    private LightspeedAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        Path agentPath = locateAgentJar();
        if (agentPath == null) {
            log("disabled: agent code source is not a regular JAR");
            return;
        }
        String agentDigest = sha256(agentPath);
        if (agentDigest == null || !ownerMatchesAgent(agentDigest)) {
            log("disabled: owning Lightspeed Mod JAR is missing or embeds a different Agent");
            return;
        }

        try {
            bootstrapJar = new JarFile(agentPath.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar);
            Class<?> hooks = Class.forName(
                    "com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks", true, null);
            Class<?> transformerType = Class.forName(
                    "com.ccr4ft3r.lightspeed.bootstrap.transform.LauncherTransformer", true, null);
            hooks.getMethod("installInstrumentation", Instrumentation.class).invoke(null, instrumentation);
            ClassFileTransformer transformer = (ClassFileTransformer) transformerType
                    .getConstructor(Consumer.class)
                    .newInstance((Consumer<String>) LightspeedAgent::log);
            hooks.getMethod("installSummaryHook").invoke(null);
            hooks.getMethod("startResourceImageLoad").invoke(null);
            instrumentation.addTransformer(transformer, false);
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (TARGET_CLASSES.contains(loadedClass.getName())) {
                    log("target was already loaded before transformer registration: " + loadedClass.getName());
                }
            }
            System.setProperty("lightspeed.bootstrapAgent.active", "true");
            System.setProperty("lightspeed.bootstrapAgent.digest", agentDigest);
            log("active: " + agentPath.getFileName());
        } catch (ReflectiveOperationException | RuntimeException | java.io.IOException exception) {
            log("disabled: bootstrap initialization failed: " + exception);
        }
    }

    private static Path locateAgentJar() {
        try {
            Path path = Path.of(LightspeedAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | RuntimeException exception) {
            log("cannot resolve agent code source: " + exception);
            return null;
        }
    }

    static boolean ownerMatchesAgent(String agentDigest) {
        String configured = System.getProperty("lightspeed.agent.owner");
        if (configured == null || configured.isBlank()) {
            return Boolean.getBoolean("lightspeed.agent.allowUnowned");
        }
        try (JarFile owner = new JarFile(Path.of(configured).toFile())) {
            var embedded = owner.getJarEntry("META-INF/lightspeed/bootstrap-agent.jar");
            if (owner.getJarEntry("META-INF/mods.toml") == null || embedded == null) {
                return false;
            }
            byte[] bytes = owner.getInputStream(embedded).readAllBytes();
            return agentDigest.equals(sha256(bytes));
        } catch (RuntimeException | java.io.IOException exception) {
            return false;
        }
    }

    private static String sha256(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (java.io.IOException exception) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void log(String message) {
        System.err.println("[Lightspeed Agent] " + message);
    }
}

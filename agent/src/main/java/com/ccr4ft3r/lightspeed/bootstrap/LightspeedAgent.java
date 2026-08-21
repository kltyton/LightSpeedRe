package com.ccr4ft3r.lightspeed.bootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;

public final class LightspeedAgent {
    private static final Set<String> TARGET_CLASSES = Set.of(
            "net.minecraftforge.fml.loading.ModDirTransformerDiscoverer",
            "net.neoforged.fml.loading.ModDirTransformerDiscoverer",
            "cpw.mods.jarhandling.impl.Jar",
            "cpw.mods.cl.ModuleClassLoader",
            "net.minecraftforge.fml.loading.moddiscovery.Scanner",
            "net.minecraftforge.eventbus.EventBus");
    private static JarFile bootstrapJar;

    private LightspeedAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        Path agentPath = locateAgentJar();
        if (agentPath == null) {
            log("disabled: agent code source is not a regular JAR");
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

    private static void log(String message) {
        System.err.println("[Lightspeed Agent] " + message);
    }
}

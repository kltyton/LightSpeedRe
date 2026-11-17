package com.ccr4ft3r.lightspeed.bootstrap;

import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.JarURLConnection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.Arrays;
import java.util.Comparator;
import com.ccr4ft3r.lightspeed.bootstrap.transform.LauncherTransformer;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.transform.ClassHierarchy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

public final class LightspeedAgent {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private LightspeedAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        initialize(arguments, instrumentation, false, null);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        initialize(arguments, instrumentation, true, null);
    }

    public static void startFromLoader(Instrumentation instrumentation, Path archive) {
        initialize("loader-early", instrumentation, true, archive);
    }

    private static void initialize(String arguments, Instrumentation instrumentation, boolean dynamic, Path archive) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            log("already active; ignoring duplicate " + (dynamic ? "agentmain" : "premain"));
            return;
        }
        Path agentPath = archive == null ? locateAgentJar() : archive.toAbsolutePath().normalize();
        if (agentPath == null || !Files.isRegularFile(agentPath)) {
            log("disabled: agent code source is not a regular JAR");
            INITIALIZED.set(false);
            return;
        }
        String agentDigest = sha256(agentPath);
        if (agentDigest == null || !ownerMatchesAgent(agentDigest)) {
            log("disabled: owning Lightspeed Mod JAR is missing or embeds a different Agent");
            INITIALIZED.set(false);
            return;
        }

        LauncherTransformer registered = null;
        try {
            BootstrapHooks.installInstrumentation(instrumentation);
            LauncherTransformer transformer = new LauncherTransformer(LightspeedAgent::log);
            BootstrapHooks.installSummaryHook();
            BootstrapHooks.startResourceImageLoad();
            Set<String> targets = LauncherTransformer.targetClassNames();
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            for (Class<?> type : loaded) {
                if (dynamic && LauncherTransformer.requiresInitialDefinition(type.getName().replace('.', '/'))) {
                    throw new IllegalStateException("Early bootstrap started after structural target " + type.getName());
                }
                ClassHierarchy.observeDefined(type);
            }
            instrumentation.addTransformer(transformer, true);
            registered = transformer;
            Arrays.sort(loaded, Comparator.comparingInt(type ->
                    type.getName().equals("cpw.mods.cl.ModuleClassLoader") ? 0 :
                    type.getName().equals("cpw.mods.jarhandling.impl.Jar") ? 1 :
                    type.getName().equals("cpw.mods.jarhandling.impl.Jar$JarModuleDataProvider") ? 2 : 3));
            for (Class<?> loadedClass : loaded) {
                if (targets.contains(loadedClass.getName())) {
                    boolean resolver = loadedClass.getName().equals("java.lang.module.Resolver");
                    boolean zipFileSystem = loadedClass.getName().equals("jdk.nio.zipfs.ZipFileSystem");
                    if ((resolver || zipFileSystem)
                            && Runtime.version().feature() != 21) {
                        continue;
                    }
                    if ((dynamic || resolver || zipFileSystem) && instrumentation.isModifiableClass(loadedClass)) {
                        try {
                            instrumentation.retransformClasses(loadedClass);
                            log("retransformed target: " + loadedClass.getName());
                        } catch (java.lang.instrument.UnmodifiableClassException | RuntimeException | LinkageError exception) {
                            if (resolver) {
                                System.clearProperty("lightspeed.bootstrapAgent.moduleGraph");
                            }
                            throw new IllegalStateException("Cannot retransform early target " + loadedClass.getName(), exception);
                        }
                    } else if (dynamic) {
                        throw new IllegalStateException("JVM cannot retransform the loaded target " + loadedClass.getName());
                    } else {
                        log("target was already loaded before transformer registration: " + loadedClass.getName());
                    }
                }
            }
            String patchFailure = System.getProperty("lightspeed.bootstrapAgent.patchFailure");
            if (patchFailure != null) throw new IllegalStateException(patchFailure);
            System.setProperty("lightspeed.bootstrapAgent.digest", agentDigest);
            System.setProperty("lightspeed.bootstrapAgent.dynamic", Boolean.toString(dynamic));
            boolean early = !dynamic || "loader-early".equals(arguments);
            System.setProperty("lightspeed.bootstrapAgent.early", Boolean.toString(early));
            if (early) BootstrapHooks.startStartupArchiveWindow();
            System.setProperty("lightspeed.bootstrapAgent.active", "true");
            log("active (" + (dynamic ? "agentmain" : "premain") + "): " + agentPath.getFileName());
        } catch (RuntimeException | LinkageError exception) {
            if (registered != null) instrumentation.removeTransformer(registered);
            System.clearProperty("lightspeed.bootstrapAgent.active");
            System.clearProperty("lightspeed.bootstrapAgent.digest");
            System.setProperty("lightspeed.bootstrapAgent.failure", exception.toString());
            INITIALIZED.set(false);
            log("disabled: bootstrap initialization failed: " + exception);
        }
    }

    private static Path locateAgentJar() {
        try {
            var source = LightspeedAgent.class.getProtectionDomain().getCodeSource();
            Path path;
            if (source != null) {
                path = Path.of(source.getLocation().toURI());
            } else {
                var resource = LightspeedAgent.class.getResource("LightspeedAgent.class");
                if (resource == null || !(resource.openConnection() instanceof JarURLConnection jar)) {
                    throw new IOException("Bootstrap class does not have an archive resource");
                }
                path = Path.of(jar.getJarFileURL().toURI());
            }
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | IOException | RuntimeException exception) {
            log("cannot resolve agent code source: " + exception);
            return null;
        }
    }

    public static boolean ownerMatchesAgent(String agentDigest) {
        String configured = System.getProperty("lightspeed.agent.owner");
        if (configured == null || configured.isBlank()) {
            return Boolean.getBoolean("lightspeed.agent.allowUnowned");
        }
        try (JarFile owner = new JarFile(Path.of(configured).toFile())) {
            var embedded = owner.getJarEntry("META-INF/lightspeed/bootstrap-agent.jar");
            if ((owner.getJarEntry("META-INF/mods.toml") == null
                    && owner.getJarEntry("META-INF/neoforge.mods.toml") == null) || embedded == null) {
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

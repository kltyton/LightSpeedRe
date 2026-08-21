package com.ccr4ft3r.lightspeed.bootstrap.runtime.discovery;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.jar.JarFile;

public final class TransformerServiceScanner {
    private static final Set<String> TRANSFORMER_SERVICES = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.minecraftforge.forgespi.locating.IModLocator",
            "net.minecraftforge.forgespi.locating.IDependencyLocator",
            "net.minecraftforge.fml.loading.ImmediateWindowProvider",
            "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "net.neoforged.neoforgespi.locating.IModFileReader",
            "net.neoforged.neoforgespi.locating.IDependencyLocator",
            "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
            "net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider");
    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private TransformerServiceScanner() {
    }

    public static boolean mayProvide(Path path) {
        CANDIDATES.increment();
        if (!isPhysicalJar(path)) {
            return true;
        }

        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (isMultiRelease(jar) || jar.getJarEntry("module-info.class") != null || hasVersionedModuleDescriptor(jar)) {
                return true;
            }
            for (String service : TRANSFORMER_SERVICES) {
                if (jar.getJarEntry("META-INF/services/" + service) != null) {
                    return true;
                }
            }
            REJECTED.increment();
            return false;
        } catch (IOException | RuntimeException exception) {
            FAILURES.increment();
            return true;
        }
    }

    public static long candidates() {
        return CANDIDATES.sum();
    }

    public static long rejected() {
        return REJECTED.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    private static boolean isPhysicalJar(Path path) {
        if (path == null) {
            return false;
        }
        FileSystem fileSystem = path.getFileSystem();
        if (!"file".equalsIgnoreCase(fileSystem.provider().getScheme())
                || !path.toString().endsWith(".jar")
                || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            return Files.size(path) > 0;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean isMultiRelease(JarFile jar) throws IOException {
        return jar.getManifest() != null
                && Boolean.parseBoolean(jar.getManifest().getMainAttributes().getValue("Multi-Release"));
    }

    private static boolean hasVersionedModuleDescriptor(JarFile jar) {
        int runtimeVersion = Runtime.version().feature();
        return jar.stream().map(entry -> entry.getName()).anyMatch(name -> {
            if (!name.startsWith("META-INF/versions/") || !name.endsWith("/module-info.class")) {
                return false;
            }
            int versionEnd = name.indexOf('/', "META-INF/versions/".length());
            if (versionEnd < 0) {
                return true;
            }
            try {
                return Integer.parseInt(name.substring("META-INF/versions/".length(), versionEnd)) <= runtimeVersion;
            } catch (NumberFormatException exception) {
                return true;
            }
        });
    }
}

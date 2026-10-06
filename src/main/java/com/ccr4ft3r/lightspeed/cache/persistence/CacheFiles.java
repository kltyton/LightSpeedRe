package com.ccr4ft3r.lightspeed.cache.persistence;

import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class CacheFiles {
    public static final File CACHE_DIR = gameDirectory()
            .resolve("lightspeed-cache")
            .resolve(gameVersion())
            .toFile();
    public static final File HAS_RESOURCE_CACHE_DIR = new File(CACHE_DIR, "hasResource");
    public static final File NAMESPACE_CACHE_DIR = new File(CACHE_DIR, "namespaces");
    public static final File RESOURCE_LIST_CACHE_DIR = new File(CACHE_DIR, "resourceLists");

    private static final Logger LOGGER = LogUtils.getLogger();

    private CacheFiles() {
    }

    private static Path gameDirectory() {
        Path configured = FMLPaths.GAMEDIR.get();
        return configured == null ? Path.of(System.getProperty("user.dir", ".")) : configured;
    }

    private static String gameVersion() {
        try {
            return SharedConstants.getCurrentVersion().getId();
        } catch (IllegalStateException exception) {
            return "uninitialized";
        }
    }

    public static Stream<File> getCacheFiles(File directory) {
        if (!directory.isDirectory()) {
            return Stream.empty();
        }
        File[] caches = directory.listFiles((ignored, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".lsc");
        });
        return caches == null ? Stream.empty() : Arrays.stream(caches).filter(File::isFile);
    }

    public static void persist(Map<?, ?> value, File file) {
        persist(value, file, true);
    }

    static boolean persistQuietly(Map<?, ?> value, File file) {
        return persist(value, file, false);
    }

    private static boolean persist(Map<?, ?> value, File file, boolean logFailure) {
        Path target = file.toPath();
        Path parent = target.getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = Files.createTempFile(parent, file.getName(), ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream))) {
                NeutralCacheCodec.write(value, output);
                output.flush();
                stream.getFD().sync();
            }
            moveIntoPlace(temporary, target);
            return true;
        } catch (Exception exception) {
            if (logFailure) {
                LOGGER.error("Cannot create cache file: {}", file, exception);
            }
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception exception) {
                    LOGGER.debug("Cannot remove temporary cache file {}", temporary, exception);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> load(File file) {
        return load(file, true);
    }

    static <K, V> Map<K, V> loadQuietly(File file) {
        return load(file, false);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> load(File file, boolean logFailure) {
        try {
            if (!file.isFile() || Files.size(file.toPath()) > NeutralCacheCodec.MAX_FILE_BYTES) {
                return new ConcurrentHashMap<>();
            }
        } catch (Exception exception) {
            return new ConcurrentHashMap<>();
        }
        try (FileInputStream stream = new FileInputStream(file);
             DataInputStream input = new DataInputStream(new BufferedInputStream(stream))) {
            return new ConcurrentHashMap<>((Map<K, V>) NeutralCacheCodec.read(input));
        } catch (Exception exception) {
            if (logFailure) {
                LOGGER.warn("Cannot load cache file {}; rebuilding it", file.getName(), exception);
            }
        }
        return new ConcurrentHashMap<>();
    }

    public static File cacheFile(File directory, String id) {
        return new File(directory, id + ".lsc");
    }

    private static void moveIntoPlace(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

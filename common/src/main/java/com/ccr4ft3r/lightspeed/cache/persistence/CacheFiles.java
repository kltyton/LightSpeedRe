package com.ccr4ft3r.lightspeed.cache.persistence;

import com.ccr4ft3r.lightspeed.platform.LoaderEnvironment;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
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
    private static final ConcurrentHashMap<Path, LoadedDigest> LOADED_DIGESTS = new ConcurrentHashMap<>();
    private static volatile Set<Path> activePersistTracker;

    private record LoadedDigest(long size, long mtimeMillis, byte[] sha256) {
    }

    private CacheFiles() {
    }

    private static Path gameDirectory() {
        Path configured = LoaderEnvironment.gameDirectory();
        return configured == null ? Path.of(System.getProperty("user.dir", ".")) : configured;
    }

    private static String gameVersion() {
        try {
            return LoaderEnvironment.gameVersion();
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
            return lower.endsWith(".lsc") || (LoaderEnvironment.CLEAN_LEGACY_CACHES && lower.endsWith(".ser"));
        });
        return caches == null ? Stream.empty() : Arrays.stream(caches).filter(File::isFile);
    }

    public static Set<Path> beginPersistTracking() {
        Set<Path> tracker = ConcurrentHashMap.newKeySet();
        activePersistTracker = tracker;
        return tracker;
    }

    public static void endPersistTracking() {
        activePersistTracker = null;
    }

    public static void clearLoadedDigests() {
        LOADED_DIGESTS.clear();
    }

    public static void pruneStaleCacheFiles(File directory, Set<Path> keepFiles) {
        getCacheFiles(directory).forEach(file -> {
            Path normalized = normalize(file.toPath());
            if (keepFiles == null || !keepFiles.contains(normalized)) {
                if (!file.delete() && file.exists()) {
                    LOGGER.warn("Lightspeed could not delete stale cache file {}", file);
                } else {
                    LOADED_DIGESTS.remove(normalized);
                }
            }
        });
    }

    public static void persist(Map<?, ?> value, File file) {
        persist(value, file, true);
    }

    static boolean persistQuietly(Map<?, ?> value, File file) {
        return persist(value, file, false);
    }

    private static boolean persist(Map<?, ?> value, File file, boolean logFailure) {
        Path target = file.toPath();
        Path normalized = normalize(target);
        if (value == null || value.isEmpty()) {
            LOADED_DIGESTS.remove(normalized);
            try {
                Files.deleteIfExists(target);
            } catch (IOException exception) {
                if (logFailure) {
                    LOGGER.debug("Cannot remove empty cache file {}", file, exception);
                }
            }
            return true;
        }

        Set<Path> tracker = activePersistTracker;
        if (tracker != null) {
            tracker.add(normalized);
        }

        byte[] encoded;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                NeutralCacheCodec.write(value, output);
            }
            encoded = buffer.toByteArray();
        } catch (Exception exception) {
            if (logFailure) {
                LOGGER.error("Cannot encode cache file: {}", file, exception);
            }
            return false;
        }

        byte[] digest = sha256(encoded);
        LoadedDigest previous = LOADED_DIGESTS.get(normalized);
        if (previous != null && previous.size() == encoded.length && Arrays.equals(previous.sha256(), digest)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
                if (attrs.isRegularFile()
                        && attrs.size() == encoded.length
                        && attrs.lastModifiedTime().toMillis() == previous.mtimeMillis()) {
                    return true;
                }
            } catch (IOException ignored) {
                // Target file disappeared or changed on disk; fall through and rewrite it.
            }
        }

        Path parent = target.getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = Files.createTempFile(parent, file.getName(), ".tmp");
            Files.write(temporary, encoded);
            moveIntoPlace(temporary, target);
            long mtime = Files.getLastModifiedTime(target).toMillis();
            LOADED_DIGESTS.put(normalized, new LoadedDigest(encoded.length, mtime, digest));
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
        Path path = file.toPath();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attrs.isRegularFile() || attrs.size() > NeutralCacheCodec.MAX_FILE_BYTES) {
                return new ConcurrentHashMap<>();
            }
        } catch (Exception exception) {
            return new ConcurrentHashMap<>();
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            if (raw.length > NeutralCacheCodec.MAX_FILE_BYTES) {
                return new ConcurrentHashMap<>();
            }
            Map<K, V> decoded;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
                decoded = new ConcurrentHashMap<>((Map<K, V>) NeutralCacheCodec.read(input));
            }
            LOADED_DIGESTS.put(normalize(path),
                    new LoadedDigest(raw.length, attrs.lastModifiedTime().toMillis(), sha256(raw)));
            return decoded;
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

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

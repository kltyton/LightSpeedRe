package com.ccr4ft3r.lightspeed.cache.persistence;

import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class CacheFiles {
    public static final File CACHE_DIR = FMLPaths.GAMEDIR.get()
            .resolve("lightspeed-cache")
            .resolve(SharedConstants.getCurrentVersion().getId())
            .toFile();
    public static final File HAS_RESOURCE_CACHE_DIR = new File(CACHE_DIR, "hasResource");
    public static final File NAMESPACE_CACHE_DIR = new File(CACHE_DIR, "namespaces");
    public static final File RESOURCE_LIST_CACHE_DIR = new File(CACHE_DIR, "resourceLists");

    private static final Logger LOGGER = LogUtils.getLogger();

    private CacheFiles() {
    }

    public static Stream<File> getCacheFiles(File directory) {
        if (!directory.isDirectory()) {
            return Stream.empty();
        }
        File[] caches = directory.listFiles((ignored, name) -> name.toLowerCase().endsWith(".ser"));
        return caches == null ? Stream.empty() : Arrays.stream(caches).filter(File::isFile);
    }

    public static void persist(Map<?, ?> value, File file) {
        Path target = file.toPath();
        Path parent = target.getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = Files.createTempFile(parent, file.getName(), ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
                 ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(stream))) {
                output.writeObject(value);
                output.flush();
                stream.getFD().sync();
            }
            moveIntoPlace(temporary, target);
        } catch (Exception exception) {
            LOGGER.error("Cannot create cache file: {}", file, exception);
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
        try (FileInputStream stream = new FileInputStream(file);
             ObjectInputStream input = new ObjectInputStream(new BufferedInputStream(stream))) {
            input.setObjectInputFilter(CacheFiles::filterCacheObject);
            Object loaded = input.readObject();
            if (loaded instanceof Map<?, ?> map) {
                return new ConcurrentHashMap<>((Map<K, V>) map);
            }
            LOGGER.warn("Cache file did not contain a map: {}", file.getName());
        } catch (Exception exception) {
            LOGGER.warn("Cannot load cache file {}; rebuilding it", file.getName(), exception);
        }
        return Maps.newConcurrentMap();
    }

    private static ObjectInputFilter.Status filterCacheObject(ObjectInputFilter.FilterInfo information) {
        if (information.depth() > 24 || information.references() > 2_000_000
                || information.arrayLength() > 2_000_000) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = information.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        String name = type.getName();
        return type.isPrimitive()
                || name.startsWith("java.lang.")
                || name.startsWith("java.util.")
                || name.startsWith("[Ljava.lang.")
                || name.equals("net.minecraft.server.packs.PackType")
                ? ObjectInputFilter.Status.ALLOWED
                : ObjectInputFilter.Status.REJECTED;
    }

    private static void moveIntoPlace(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

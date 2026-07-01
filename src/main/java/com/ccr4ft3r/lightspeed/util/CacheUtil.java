package com.ccr4ft3r.lightspeed.util;

import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class CacheUtil {

    public static final File CACHE_DIR = Paths.get(FMLPaths.GAMEDIR.get().toString(), "lightspeed-cache",
        SharedConstants.getCurrentVersion().getId()).toFile();
    public static final File HAS_RESOURCE_CACHE_DIR = new File(CACHE_DIR, "hasResource");
    public static final File NAMESPACE_CACHE_DIR = new File(CACHE_DIR, "namespaces");

    public static Stream<File> getCacheFiles(File dir) {
        if (!dir.isDirectory()) {
            return Stream.empty();
        }
        File[] caches = dir.listFiles((dir1, name) -> name.toLowerCase().endsWith(".ser"));
        if (caches == null)
            return Stream.empty();
        return Arrays.stream(caches)
            .filter(file -> !file.isDirectory());
    }

    public static void persist(Map<?, ?> toPersist, File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            LogUtils.getLogger().warn("Cannot create cache directory: {}", parent);
        }
        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(fos))) {
            oos.writeObject(toPersist);
            oos.flush();
        } catch (Exception e) {
            LogUtils.getLogger().error("Cannot create cache file: {}", file, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> load(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(fis))) {
            Object loaded = ois.readObject();
            if (loaded instanceof Map<?, ?> map) {
                return new ConcurrentHashMap<>((Map<K, V>) map);
            }
            LogUtils.getLogger().warn("Cache file did not contain a map: {}", file.getName());
        } catch (Exception e) {
            LogUtils.getLogger().error("Cannot load cache file: {}", file.getName(), e);
        }
        return Maps.newConcurrentMap();
    }
}

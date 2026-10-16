package com.ccr4ft3r.lightspeed.util;

import com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles;

import java.io.File;
import java.util.Map;
import java.util.stream.Stream;

/** Retains resource-cache callers while using bounded neutral values and atomic publication. */
public final class CacheUtil {
    public static final File CACHE_DIR = CacheFiles.CACHE_DIR;
    public static final File HAS_RESOURCE_CACHE_DIR = CacheFiles.HAS_RESOURCE_CACHE_DIR;
    public static final File NAMESPACE_CACHE_DIR = CacheFiles.NAMESPACE_CACHE_DIR;
    public static final File RESOURCE_LIST_CACHE_DIR = CacheFiles.RESOURCE_LIST_CACHE_DIR;

    private CacheUtil() { }

    public static Stream<File> getCacheFiles(File directory) {
        return CacheFiles.getCacheFiles(directory);
    }

    public static File cacheFile(File directory, String id) {
        return CacheFiles.cacheFile(directory, id);
    }

    public static void persist(Map<?, ?> value, File file) {
        CacheFiles.persist(value, neutralFile(file));
    }

    public static <K, V> Map<K, V> load(File file) {
        return CacheFiles.load(neutralFile(file));
    }

    private static File neutralFile(File file) {
        String name = file.getName();
        if (name.endsWith(".ser")) {
            return cacheFile(file.getParentFile(), name.substring(0, name.length() - 4));
        }
        return file;
    }
}

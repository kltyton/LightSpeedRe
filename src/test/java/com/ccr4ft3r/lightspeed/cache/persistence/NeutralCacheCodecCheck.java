package com.ccr4ft3r.lightspeed.cache.persistence;

import net.minecraft.server.packs.PackType;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NeutralCacheCodecCheck {
    private NeutralCacheCodecCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-neutral-cache-");
        try {
            Map<Object, Object> source = new LinkedHashMap<>();
            source.put("exists", true);
            source.put(PackType.CLIENT_RESOURCES, Set.of("minecraft", "lightspeed"));
            source.put(PackType.SERVER_DATA, Map.of(
                    "minecraft", List.of("recipes/a.json", "tags/b.json")));

            File first = CacheFiles.cacheFile(directory.toFile(), "first");
            File second = CacheFiles.cacheFile(directory.toFile(), "second");
            CacheFiles.persist(source, first);
            Map<Object, Object> loaded = CacheFiles.load(first);
            require(loaded.equals(source), "neutral cache round trip changed values");
            require(loaded instanceof ConcurrentHashMap
                            && loaded.get(PackType.SERVER_DATA) instanceof ConcurrentHashMap,
                    "decoded maps did not retain concurrent mutation safety");

            Map<Object, Object> reversed = new LinkedHashMap<>();
            reversed.put(PackType.SERVER_DATA, source.get(PackType.SERVER_DATA));
            reversed.put(PackType.CLIENT_RESOURCES, source.get(PackType.CLIENT_RESOURCES));
            reversed.put("exists", true);
            CacheFiles.persist(reversed, second);
            require(java.util.Arrays.equals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath())),
                    "neutral cache encoding depends on map iteration order");

            byte[] intact = Files.readAllBytes(first.toPath());
            require(!CacheFiles.persistQuietly(Map.of("unsupported", Path.of("value")), first),
                    "unsupported cache value was accepted");
            require(java.util.Arrays.equals(intact, Files.readAllBytes(first.toPath())),
                    "unsupported cache value replaced the last valid file");

            Files.write(first.toPath(), new byte[]{0, 1, 2, 3});
            require(CacheFiles.loadQuietly(first).isEmpty(), "corrupt neutral cache did not fail open");
            Files.write(first.toPath(), java.util.Arrays.copyOf(intact, intact.length - 1));
            require(CacheFiles.loadQuietly(first).isEmpty(), "truncated neutral cache did not fail open");
            require(CacheFiles.cacheFile(directory.toFile(), "name").getName().equals("name.lsc"),
                    "neutral cache extension is not version-independent");
            System.out.println("NEUTRAL_CACHE_CODEC_OK");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

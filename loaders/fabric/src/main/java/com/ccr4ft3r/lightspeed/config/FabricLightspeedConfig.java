package com.ccr4ft3r.lightspeed.config;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Startup preferences stored in Fabric's configuration directory. */
public final class FabricLightspeedConfig {
    private static boolean loaded;
    public boolean dedicatedResourceReloadExecutor = true;
    public boolean parallelResourceLookup = true;
    public boolean cacheResourceExistence = true;
    public boolean verifyJarHash = false;
    public boolean isolateModdedResourceReloadFailures = false;
    public List<String> isolatedResourceReloadListenerPatterns = List.of();

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        Path file = FabricLoader.getInstance().getConfigDir().resolve("lightspeed.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                    gson.toJson(new FabricLightspeedConfig(), writer);
                }
            }
            FabricLightspeedConfig config;
            try (Reader reader = Files.newBufferedReader(file)) {
                config = gson.fromJson(reader, FabricLightspeedConfig.class);
            }
            if (config == null || config.isolatedResourceReloadListenerPatterns == null
                    || config.isolatedResourceReloadListenerPatterns.stream()
                    .anyMatch(value -> value == null || value.isBlank() || value.equals("*"))) {
                throw new IllegalArgumentException("Resource reload isolation requires explicit class-name prefixes");
            }
            GlobalCache.shouldAsyncPreloadPacks = false;
            GlobalCache.shouldUseDedicatedResourceReloadExecutor = config.dedicatedResourceReloadExecutor;
            GlobalCache.shouldParallelizeResourcePackLookup = config.parallelResourceLookup;
            GlobalCache.shouldCacheResourceExistence = config.cacheResourceExistence;
            GlobalCache.shouldVerifyJarHash = config.verifyJarHash;
            GlobalCache.shouldIsolateModdedResourceReloadFailures = config.isolateModdedResourceReloadFailures;
            GlobalCache.isolatedResourceReloadListenerPatterns = List.copyOf(config.isolatedResourceReloadListenerPatterns);
            loaded = true;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot read Lightspeed configuration " + file, exception);
        }
    }
}

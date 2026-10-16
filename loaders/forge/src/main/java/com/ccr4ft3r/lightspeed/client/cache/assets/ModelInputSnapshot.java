package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.ccr4ft3r.lightspeed.cache.assets.SnapshotFileStore;
import com.ccr4ft3r.lightspeed.cache.assets.SnapshotMode;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

public final class ModelInputSnapshot {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final boolean ENABLED = SnapshotMode.modelInputsEnabled(
            System.getProperty("lightspeed.modelInputSnapshot"));
    private static final SnapshotFileStore MODELS = ENABLED ? store("model-json-v1.bin", 0x4c534d4a) : null;
    private static final SnapshotFileStore BLOCKSTATES = ENABLED ? store("blockstate-json-v1.bin", 0x4c53424a) : null;

    private ModelInputSnapshot() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static CompletableFuture<Map<ResourceLocation, BlockModel>> loadBlockModels(
            ResourceManager resources, Executor executor) {
        return CompletableFuture.supplyAsync(() -> ModelBakery.MODEL_LISTER.listMatchingResources(resources), executor)
                .thenCompose(found -> {
                    String fingerprint = packFingerprint(resources);
                    List<CompletableFuture<Pair<ResourceLocation, BlockModel>>> tasks = new ArrayList<>(found.size());
                    found.forEach((location, resource) -> tasks.add(CompletableFuture.supplyAsync(() -> {
                        String key = fingerprint + '|' + location + '|' + resource.sourcePackId();
                        try {
                            return Pair.of(location, parseModel(bytesWithFallback(MODELS, key, resource)));
                        } catch (Exception exception) {
                            LOGGER.error("Failed to load snapshotted model {}", location, exception);
                            return null;
                        }
                    }, executor)));
                    return Util.sequence(tasks).thenApply(values -> values.stream()
                            .filter(Objects::nonNull)
                            .collect(Collectors.toUnmodifiableMap(Pair::getFirst, Pair::getSecond)))
                            .thenApply(models -> {
                                LOGGER.info("Lightspeed model input snapshot prepared {} models", models.size());
                                return models;
                            });
                });
    }

    public static CompletableFuture<Map<ResourceLocation, List<ModelBakery.LoadedJson>>> loadBlockStates(
            ResourceManager resources, Executor executor) {
        return CompletableFuture.supplyAsync(() -> ModelBakery.BLOCKSTATE_LISTER
                        .listMatchingResourceStacks(resources), executor)
                .thenCompose(found -> {
                    String fingerprint = packFingerprint(resources);
                    List<CompletableFuture<Pair<ResourceLocation, List<ModelBakery.LoadedJson>>>> tasks =
                            new ArrayList<>(found.size());
                    found.forEach((location, stack) -> tasks.add(CompletableFuture.supplyAsync(() -> {
                        List<ModelBakery.LoadedJson> decoded = new ArrayList<>(stack.size());
                        for (int index = 0; index < stack.size(); index++) {
                            Resource resource = stack.get(index);
                            String key = fingerprint + '|' + location + '|' + index + '|' + resource.sourcePackId();
                            try {
                                decoded.add(new ModelBakery.LoadedJson(resource.sourcePackId(),
                                        parseJson(bytesWithFallback(BLOCKSTATES, key, resource))));
                            } catch (Exception exception) {
                                LOGGER.error("Failed to load snapshotted blockstate {} from {}",
                                        location, resource.sourcePackId(), exception);
                            }
                        }
                        return Pair.of(location, decoded);
                    }, executor)));
                    return Util.sequence(tasks).thenApply(values -> values.stream()
                            .filter(Objects::nonNull)
                            .collect(Collectors.toUnmodifiableMap(Pair::getFirst, Pair::getSecond)))
                            .thenApply(blockstates -> {
                                LOGGER.info("Lightspeed blockstate input snapshot prepared {} stacks", blockstates.size());
                                return blockstates;
                            });
                });
    }

    public static void persist() {
        if (ENABLED) {
            MODELS.persist();
            BLOCKSTATES.persist();
        }
    }

    public static long hits() {
        return ENABLED ? MODELS.hits() + BLOCKSTATES.hits() : 0;
    }

    public static long misses() {
        return ENABLED ? MODELS.misses() + BLOCKSTATES.misses() : 0;
    }

    public static long failures() {
        return ENABLED ? MODELS.failures() + BLOCKSTATES.failures() : 0;
    }

    private static BlockModel parseModel(byte[] bytes) throws Exception {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return BlockModel.fromStream(reader);
        }
    }

    private static JsonObject parseJson(byte[] bytes) throws Exception {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return GsonHelper.parse(reader);
        }
    }

    private static byte[] bytesWithFallback(SnapshotFileStore store, String key, Resource resource) throws Exception {
        byte[] cached = store.get(key);
        if (cached != null) {
            return cached;
        }
        try (InputStream input = resource.open()) {
            byte[] bytes = input.readAllBytes();
            store.put(key, bytes);
            return bytes;
        }
    }

    private static String packFingerprint(ResourceManager resources) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        resources.listPacks().map(pack -> pack.packId()).sorted().forEach(id -> {
            digest.update(id.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static SnapshotFileStore store(String file, int magic) {
        return new SnapshotFileStore(assetDirectory().resolve(file), magic, 1,
                200_000, MAX_JSON_BYTES, 768L * 1024L * 1024L);
    }

    private static Path assetDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("lightspeed-cache")
                .resolve(SharedConstants.getCurrentVersion().getId()).resolve("client-assets");
    }
}

package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.ccr4ft3r.lightspeed.cache.assets.SnapshotFileStore;
import net.minecraft.SharedConstants;
import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class FontWarmupSnapshot {
    private static final byte[] PRESENT = {1};
    private static final ThreadLocal<String> PENDING = new ThreadLocal<>();
    private static final SnapshotFileStore STORE = new SnapshotFileStore(
            assetDirectory().resolve("font-warmup-v1.bin"), 0x4c534657, 1,
            65_536, 8, 1024L * 1024L);

    private FontWarmupSnapshot() {
    }

    public static boolean shouldSkip(List<GlyphProvider> providers) {
        String signature = signature(providers);
        if (STORE.get(signature) != null) {
            return true;
        }
        PENDING.set(signature);
        return false;
    }

    public static void recordCompleted() {
        String signature = PENDING.get();
        PENDING.remove();
        if (signature != null) {
            STORE.put(signature, PRESENT);
        }
    }

    public static void persist() {
        STORE.persist();
    }

    public static long hits() {
        return STORE.hits();
    }

    public static long misses() {
        return STORE.misses();
    }

    public static long failures() {
        return STORE.failures();
    }

    private static String signature(List<GlyphProvider> providers) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        for (GlyphProvider provider : providers) {
            digest.update(provider.getClass().getName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            var glyphs = provider.getSupportedGlyphs();
            digest.update(Integer.toString(glyphs.size()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(glyphs.hashCode()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path assetDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("lightspeed-cache")
                .resolve(SharedConstants.getCurrentVersion().getId()).resolve("client-assets");
    }
}

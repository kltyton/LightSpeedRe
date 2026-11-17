package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.ccr4ft3r.lightspeed.cache.assets.SnapshotFileStore;
import net.minecraft.SharedConstants;
import com.mojang.blaze3d.font.GlyphProvider;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import com.ccr4ft3r.lightspeed.platform.LoaderEnvironment;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;

public final class FontWarmupSnapshot {
    private static final int MAX_PLAN_BYTES = 16 * 1024 * 1024;
    private static final LongAdder PLAN_FAILURES = new LongAdder();
    private static final SnapshotFileStore STORE = new SnapshotFileStore(
            assetDirectory().resolve("font-warmup-v2.bin"), 0x4c534657, 2,
            65_536, MAX_PLAN_BYTES, 64L * 1024L * 1024L);

    private FontWarmupSnapshot() {
    }

    /** Reuses the native character order, then warms the providers created for this reload. */
    public static void warmup(List<GlyphProvider> providers, GlyphProvider missing) {
        providers.add(0, missing);
        warmupExisting(providers);
    }

    /** Warms every provider in its current native order. */
    public static void warmupExisting(List<GlyphProvider> providers) {
        MessageDigest digest = sha256();
        ByteBuffer signatureBytes = ByteBuffer.allocate(4096);
        List<IntArrayList> supported = new ArrayList<>(providers.size());
        updateInt(digest, providers.size());
        for (GlyphProvider provider : providers) {
            IntSet glyphs = provider.getSupportedGlyphs();
            IntArrayList characters = new IntArrayList(glyphs.size());
            IntIterator iterator = glyphs.iterator();
            while (iterator.hasNext()) characters.add(iterator.nextInt());
            supported.add(characters);
            updateInt(digest, characters.size());
            for (int index = 0; index < characters.size(); index++) {
                if (!signatureBytes.hasRemaining()) {
                    digest.update(signatureBytes.array());
                    signatureBytes.clear();
                }
                signatureBytes.putInt(characters.getInt(index));
            }
            digest.update(signatureBytes.array(), 0, signatureBytes.position());
            signatureBytes.clear();
        }
        String signature = HexFormat.of().formatHex(digest.digest());
        byte[] cached = STORE.get(signature);
        IntArrayList order = decode(signature, cached);
        if (cached != null && order == null) {
            PLAN_FAILURES.increment();
            LogUtils.getLogger().warn("Ignored invalid font warmup plan {}", signature);
        }
        if (order == null) {
            IntOpenHashSet characters = new IntOpenHashSet();
            for (IntArrayList glyphs : supported) characters.addAll(glyphs);
            order = new IntArrayList(characters.size());
            characters.forEach((IntConsumer) order::add);
            byte[] encoded = encode(signature, order);
            if (encoded != null) STORE.put(signature, encoded);
        }
        for (int index = 0; index < order.size(); index++) {
            int character = order.getInt(index);
            if (character == 32) continue;
            for (GlyphProvider provider : Lists.reverse(providers)) {
                if (provider.getGlyph(character) != null) break;
            }
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
        return STORE.failures() + PLAN_FAILURES.sum();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static byte[] encode(String signature, IntArrayList order) {
        long length = 32L + (long) order.size() * Integer.BYTES;
        if (length > MAX_PLAN_BYTES) return null;
        ByteBuffer bytes = ByteBuffer.allocate((int) length);
        bytes.position(32);
        for (int index = 0; index < order.size(); index++) bytes.putInt(order.getInt(index));
        byte[] encoded = bytes.array();
        byte[] checksum = checksum(signature, encoded);
        System.arraycopy(checksum, 0, encoded, 0, checksum.length);
        return encoded;
    }

    private static IntArrayList decode(String signature, byte[] encoded) {
        if (encoded == null || encoded.length < 32 || (encoded.length - 32) % Integer.BYTES != 0) return null;
        byte[] expected = checksum(signature, encoded);
        for (int index = 0; index < expected.length; index++) {
            if (encoded[index] != expected[index]) return null;
        }
        ByteBuffer bytes = ByteBuffer.wrap(encoded);
        bytes.position(32);
        IntArrayList order = new IntArrayList(bytes.remaining() / Integer.BYTES);
        while (bytes.hasRemaining()) order.add(bytes.getInt());
        return order;
    }

    private static byte[] checksum(String signature, byte[] encoded) {
        MessageDigest digest = sha256();
        digest.update(signature.getBytes(StandardCharsets.US_ASCII));
        digest.update(encoded, 32, encoded.length - 32);
        return digest.digest();
    }

    private static Path assetDirectory() {
        return LoaderEnvironment.gameDirectory().resolve("lightspeed-cache")
                .resolve(SharedConstants.getCurrentVersion().getId()).resolve("client-assets");
    }
}

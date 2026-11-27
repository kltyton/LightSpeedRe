package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.ccr4ft3r.lightspeed.cache.assets.SnapshotFileStore;
import com.ccr4ft3r.lightspeed.cache.assets.SnapshotMode;
import com.ccr4ft3r.lightspeed.mixin.client.NativeImageAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import com.ccr4ft3r.lightspeed.platform.LoaderEnvironment;
import com.mojang.logging.LogUtils;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

public final class NativeImageSnapshot {
    private static final int MAX_IMAGE_BYTES = 256 * 1024 * 1024;
    private static final int MIN_ENCODED_PNG_BYTES = 2048;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENVIRONMENT_PROPERTY = "lightspeed.nativeImageSnapshot.environment";
    private static final String ENVIRONMENT = System.getProperty(ENVIRONMENT_PROPERTY, "");
    private static final boolean REQUESTED = SnapshotMode.nativeImagesEnabled(
            System.getProperty("lightspeed.nativeImageSnapshot"));
    private static final boolean ENABLED = REQUESTED && PixelSnapshotCodec.validEnvironment(ENVIRONMENT);
    static {
        if (REQUESTED && !ENABLED) {
            LOGGER.warn("Lightspeed native image snapshot disabled: {} must be a 64-character SHA-256 digest",
                    ENVIRONMENT_PROPERTY);
        }
    }
    private static final LongAdder RESTORES = new LongAdder();
    private static final LongAdder RESTORE_FAILURES = new LongAdder();
    private static final SnapshotFileStore STORE = ENABLED ? new SnapshotFileStore(
            assetDirectory().resolve("native-images-v4-" + ENVIRONMENT.toLowerCase(java.util.Locale.ROOT) + ".bin"),
            0x4c534e49, PixelSnapshotCodec.VERSION,
            100_000, MAX_IMAGE_BYTES + PixelSnapshotCodec.HEADER_BYTES, 1024L * 1024L * 1024L) : null;

    private NativeImageSnapshot() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static NativeImage read(InputStream input) throws IOException {
        byte[] encoded = NativeImageSnapshotInput.readAllBytesQuietly(input);
        boolean eligible = ENABLED && encoded.length >= MIN_ENCODED_PNG_BYTES;
        String key = null;
        if (eligible) {
            key = PixelSnapshotCodec.key(encoded, ENVIRONMENT);
            ByteBuffer snapshot = STORE.getReadOnly(key);
            if (snapshot != null) {
                NativeImage restored = restore(snapshot);
                if (restored != null) {
                    RESTORES.increment();
                    return restored;
                }
                RESTORE_FAILURES.increment();
            }
        }
        ByteBuffer compressed = MemoryUtil.memAlloc(encoded.length);
        NativeImage decoded;
        try {
            compressed.put(encoded).flip();
            decoded = NativeImage.read(compressed);
        } finally {
            MemoryUtil.memFree(compressed);
        }
        if (eligible) {
            try {
                record(key, decoded);
            } catch (RuntimeException exception) {
                LOGGER.debug("Lightspeed native image snapshot record skipped", exception);
            }
        }
        return decoded;
    }

    public static void persist() {
        if (ENABLED) {
            STORE.persist();
            STORE.clearInMemory();
        }
    }

    public static long hits() {
        return ENABLED ? STORE.hits() : 0;
    }

    public static long misses() {
        return ENABLED ? STORE.misses() : 0;
    }

    public static long failures() {
        return ENABLED ? STORE.failures() : 0;
    }

    public static long restores() {
        return RESTORES.sum();
    }

    public static long restoreFailures() {
        return RESTORE_FAILURES.sum();
    }

    private static void record(String key, NativeImage image) {
        long width = image.getWidth();
        long height = image.getHeight();
        long components = image.format().components();
        if (width <= 0 || height <= 0 || components <= 0
                || width > Long.MAX_VALUE / height
                || width * height > Long.MAX_VALUE / components) {
            return;
        }
        long lengthLong = width * height * components;
        if (lengthLong <= 0 || lengthLong > MAX_IMAGE_BYTES) {
            return;
        }
        int length = (int) lengthLong;
        ByteBuffer source = MemoryUtil.memByteBuffer(((NativeImageAccessor) (Object) image).lightspeed$pixels(), length);
        ByteBuffer pixels = source.duplicate();
        pixels.position(0).limit(length);
        byte[] encoded = PixelSnapshotCodec.encode(image.format().ordinal(), image.getWidth(), image.getHeight(), pixels,
                MAX_IMAGE_BYTES);
        if (encoded != null) {
            STORE.put(key, encoded);
        }
    }

    private static NativeImage restore(ByteBuffer snapshot) {
        try {
            PixelSnapshotCodec.Decoded decoded = PixelSnapshotCodec.decode(snapshot, MAX_IMAGE_BYTES);
            if (decoded == null) return null;
            int formatId = decoded.format();
            int width = decoded.width();
            int height = decoded.height();
            NativeImage.Format[] formats = NativeImage.Format.values();
            if (formatId < 0 || formatId >= formats.length || width <= 0 || height <= 0) {
                return null;
            }
            NativeImage.Format format = formats[formatId];
            long expectedLong = checkedPixelBytes(width, height, format.components());
            if (expectedLong != decoded.pixels().remaining()) return null;
            NativeImage image = new NativeImage(format, width, height, false);
            boolean success = false;
            try {
                ByteBuffer target = MemoryUtil.memByteBuffer(
                        ((NativeImageAccessor) (Object) image).lightspeed$pixels(), (int) expectedLong);
                target.put(decoded.pixels());
                success = true;
                return image;
            } finally {
                if (!success) {
                    image.close();
                }
            }
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long checkedPixelBytes(long width, long height, long components) {
        if (width <= 0 || height <= 0 || components <= 0
                || width > Long.MAX_VALUE / height
                || width * height > Long.MAX_VALUE / components) {
            return -1;
        }
        return width * height * components;
    }

    private static Path assetDirectory() {
        return LoaderEnvironment.gameDirectory().resolve("lightspeed-cache")
                .resolve(SharedConstants.getCurrentVersion().getId()).resolve("client-assets");
    }

}

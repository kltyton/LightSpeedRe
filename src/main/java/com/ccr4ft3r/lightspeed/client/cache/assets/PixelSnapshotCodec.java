package com.ccr4ft3r.lightspeed.client.cache.assets;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Pure Java wire codec for native pixel snapshots; it does not allocate NativeImage objects. */
public final class PixelSnapshotCodec {
    public static final int VERSION = 3;
    public static final int DIGEST_BYTES = 32;
    public static final int HEADER_BYTES = Integer.BYTES * 4 + DIGEST_BYTES;

    private PixelSnapshotCodec() {
    }

    public static byte[] encode(int format, int width, int height, ByteBuffer pixels, int maxBytes) {
        long size = pixelBytes(width, height, componentsForFormat(format));
        if (size <= 0 || size > maxBytes || size > Integer.MAX_VALUE - HEADER_BYTES
                || pixels.remaining() != (int) size) {
            return null;
        }
        ByteBuffer output = ByteBuffer.allocate(HEADER_BYTES + (int) size);
        output.putInt(VERSION).putInt(format).putInt(width).putInt(height);
        output.put(digest(output.duplicate().flip(), pixels.duplicate()));
        output.put(pixels.duplicate());
        return output.array();
    }

    public static Decoded decode(byte[] snapshot, int maxBytes) {
        if (snapshot == null || snapshot.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer input = ByteBuffer.wrap(snapshot);
        int version = input.getInt();
        int format = input.getInt();
        int width = input.getInt();
        int height = input.getInt();
        byte[] expected = new byte[DIGEST_BYTES];
        input.get(expected);
        long size = pixelBytes(width, height, componentsForFormat(format));
        if (version != VERSION || size <= 0 || size > maxBytes || size != input.remaining()
                || !MessageDigest.isEqual(expected, digest(metadata(input), input.duplicate()))) {
            return null;
        }
        return new Decoded(format, width, height, input.slice());
    }

    static String key(byte[] input, String environment) {
        if (input == null || !validEnvironment(environment)) return null;
        return HexFormat.of().formatHex(digest(ByteBuffer.wrap(
                environment.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)),
                ByteBuffer.wrap(input)));
    }

    static boolean validEnvironment(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                character >= '0' && character <= '9'
                        || character >= 'a' && character <= 'f'
                        || character >= 'A' && character <= 'F');
    }

    private static long pixelBytes(long width, long height, long components) {
        if (width <= 0 || height <= 0 || components <= 0
                || width > Long.MAX_VALUE / height
                || width * height > Long.MAX_VALUE / components) {
            return -1;
        }
        return width * height * components;
    }

    private static int componentsForFormat(int format) {
        return switch (format) {
            case 0 -> 4;
            case 1 -> 3;
            case 2 -> 2;
            case 3 -> 1;
            default -> -1;
        };
    }

    private static ByteBuffer metadata(ByteBuffer input) {
        ByteBuffer metadata = input.duplicate();
        metadata.position(0).limit(Integer.BYTES * 4);
        return metadata;
    }

    private static byte[] digest(ByteBuffer metadata, ByteBuffer pixels) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(metadata);
            digest.update(pixels);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Decoded(int format, int width, int height, ByteBuffer pixels) {
    }
}

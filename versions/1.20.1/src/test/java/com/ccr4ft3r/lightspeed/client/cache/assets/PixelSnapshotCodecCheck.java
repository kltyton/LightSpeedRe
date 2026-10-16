package com.ccr4ft3r.lightspeed.client.cache.assets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.io.IOException;
import java.io.InputStream;

/** Main-style regression for the pure pixel snapshot wire format. */
public final class PixelSnapshotCodecCheck {
    private PixelSnapshotCodecCheck() {
    }

    public static void main(String[] args) throws Exception {
        byte[] pixels = new byte[16];
        for (int index = 0; index < pixels.length; index++) pixels[index] = (byte) index;
        byte[] encoded = PixelSnapshotCodec.encode(0, 1, 4, ByteBuffer.wrap(pixels), 256);
        require(encoded != null, "encode");
        PixelSnapshotCodec.Decoded decoded = PixelSnapshotCodec.decode(encoded, 256);
        require(decoded != null && decoded.width() == 1 && decoded.height() == 4
                && same(decoded.pixels(), pixels), "round trip");
        for (int format = 0; format < 4; format++) {
            int components = 4 - format;
            byte[] value = new byte[components * 2];
            byte[] roundTrip = PixelSnapshotCodec.encode(format, 1, 2, ByteBuffer.wrap(value), 256);
            require(roundTrip != null && PixelSnapshotCodec.decode(roundTrip, 256) != null,
                    "format " + format);
        }

        byte[] swapped = encoded.clone();
        swapInt(swapped, 8, 4);
        swapInt(swapped, 12, 1);
        require(PixelSnapshotCodec.decode(swapped, 256) == null, "metadata mutation");
        byte[] flipped = encoded.clone();
        flipped[PixelSnapshotCodec.HEADER_BYTES] ^= 1;
        require(PixelSnapshotCodec.decode(flipped, 256) == null, "pixel mutation");
        byte[] version = encoded.clone();
        version[3]++;
        require(PixelSnapshotCodec.decode(version, 256) == null, "version mutation");
        require(PixelSnapshotCodec.decode(Arrays.copyOf(encoded, encoded.length - 1), 256) == null,
                "truncation");
        require(PixelSnapshotCodec.encode(0, Integer.MAX_VALUE, Integer.MAX_VALUE,
                ByteBuffer.allocate(0), 256) == null, "overflow");
        require(PixelSnapshotCodec.encode(0, 16, 16, ByteBuffer.allocate(1024), 32) == null,
                "oversize");
        String environment = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        require(PixelSnapshotCodec.validEnvironment(environment)
                && PixelSnapshotCodec.validEnvironment(environment.toUpperCase()), "environment validation");
        require(!PixelSnapshotCodec.validEnvironment(null)
                && !PixelSnapshotCodec.validEnvironment("xyz")
                && !PixelSnapshotCodec.validEnvironment(environment.substring(1)), "invalid environment");
        require(PixelSnapshotCodec.key(pixels, environment).equals(
                PixelSnapshotCodec.key(pixels, environment.toUpperCase())), "environment normalization");
        require(!PixelSnapshotCodec.key(pixels, environment).equals(
                PixelSnapshotCodec.key(pixels, environment.substring(0, 63) + "0")), "environment key");
        byte[] changed = pixels.clone();
        changed[0]++;
        require(!PixelSnapshotCodec.key(pixels, environment).equals(
                PixelSnapshotCodec.key(changed, environment)), "input key");
        verifyQuietCloseSemantics();
    }

    private static boolean same(ByteBuffer actual, byte[] expected) {
        byte[] value = new byte[actual.remaining()];
        actual.get(value);
        return Arrays.equals(value, expected);
    }

    private static void swapInt(byte[] value, int offset, int replacement) {
        ByteBuffer.wrap(value).putInt(offset, replacement);
    }

    private static void verifyQuietCloseSemantics() throws IOException {
        byte[] value = NativeImageSnapshotInput.readAllBytesQuietly(new ThrowingCloseStream(false));
        require(value.length == 0, "quiet close");
        try {
            NativeImageSnapshotInput.readAllBytesQuietly(new ThrowingCloseStream(true));
            throw new AssertionError("read failure");
        } catch (IOException exception) {
            require(exception.getMessage().equals("read"), "read exception preserved");
            require(exception.getSuppressed().length == 0, "close not suppressed");
        }
    }

    private static final class ThrowingCloseStream extends InputStream {
        private final boolean failRead;

        private ThrowingCloseStream(boolean failRead) {
            this.failRead = failRead;
        }

        @Override
        public int read() throws IOException {
            if (failRead) throw new IOException("read");
            return -1;
        }

        @Override
        public void close() throws IOException {
            throw new IOException("close");
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

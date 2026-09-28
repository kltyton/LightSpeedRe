package com.ccr4ft3r.lightspeed.client.cache.assets;

import java.io.IOException;
import java.io.InputStream;

final class NativeImageSnapshotInput {
    private NativeImageSnapshotInput() {
    }

    static byte[] readAllBytesQuietly(InputStream input) throws IOException {
        try {
            return input.readAllBytes();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Matches IOUtils.closeQuietly used by the vanilla NativeImage path.
            }
        }
    }
}

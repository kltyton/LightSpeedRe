package com.ccr4ft3r.lightspeed.cache.assets;

public final class SnapshotMode {
    private SnapshotMode() {
    }

    public static boolean nativeImagesEnabled(String value) {
        return value != null && Boolean.parseBoolean(value);
    }

    public static boolean modelInputsEnabled(String value) {
        return value != null && Boolean.parseBoolean(value);
    }

    public static boolean shaderProgramsEnabled(String value) {
        return value == null || Boolean.parseBoolean(value);
    }
}

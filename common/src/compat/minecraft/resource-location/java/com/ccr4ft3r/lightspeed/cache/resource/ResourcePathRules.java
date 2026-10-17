package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.FileUtil;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** Native path parsing and file selection for the mapped Minecraft resource API. */
public final class ResourcePathRules {
    private ResourcePathRules() {
    }

    public static Optional<String> error(String path) {
        return FileUtil.decomposePath(path).error().map(result -> result.message());
    }

    public static boolean includeFile(Path file, BasicFileAttributes attributes) {
        return attributes.isRegularFile();
    }
}

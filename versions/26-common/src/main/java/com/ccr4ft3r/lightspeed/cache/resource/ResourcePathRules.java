package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.SharedConstants;
import net.minecraft.util.FileUtil;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** Preserves the modern resource parser and its development-file exclusion. */
public final class ResourcePathRules {
    private ResourcePathRules() {
    }

    public static Optional<String> error(String path) {
        return FileUtil.decomposePath(path).error().map(result -> result.message());
    }

    public static boolean includeFile(Path file, BasicFileAttributes attributes) {
        return attributes.isRegularFile() && (!SharedConstants.IS_RUNNING_IN_IDE
                || !file.getFileName().toString().equalsIgnoreCase(".ds_store"));
    }
}

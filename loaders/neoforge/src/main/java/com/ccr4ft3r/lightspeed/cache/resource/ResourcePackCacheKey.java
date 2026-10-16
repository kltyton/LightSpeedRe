package com.ccr4ft3r.lightspeed.cache.resource;

import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.commons.io.FilenameUtils;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ResourcePackCacheKey {

    private ResourcePackCacheKey() {
    }

    public static String from(IModFile modFile, boolean verifyJarHash) {
        String identity = modFile.getModFileInfo().moduleName() + modFile.getModFileInfo().versionString()
                + "-" + FilenameUtils.getBaseName(modFile.getFilePath().toString()).replaceAll("[^a-zA-Z0-9.-]", "");
        if (!verifyJarHash) {
            return identity;
        }
        Path path = modFile.getFilePath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return identity + "-sha256-" + JarContentHash.get(path);
        } catch (UncheckedIOException exception) {
            return null;
        }
    }

}

package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraftforge.forgespi.locating.IModFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ResourcePackCacheKey {
    private ResourcePackCacheKey() {
    }

    public static String from(IModFile modFile) {
        String module = sanitize(modFile.getModFileInfo().moduleName());
        String version = sanitize(modFile.getModFileInfo().versionString());
        Path path = modFile.getFilePath().toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String identity = path + "|" + attributes.size() + "|" + attributes.lastModifiedTime().toMillis()
                    + '|' + String.valueOf(attributes.fileKey());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return module + '-' + version + '-' + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}

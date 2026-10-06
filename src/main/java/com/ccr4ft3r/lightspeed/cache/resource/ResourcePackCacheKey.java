package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraftforge.forgespi.locating.IModFile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ResourcePackCacheKey {
    private static final ConcurrentMap<Path, String> JAR_HASHES = new ConcurrentHashMap<>();

    private ResourcePackCacheKey() {
    }

    public static String from(IModFile modFile, boolean verifyJarHash) {
        String module = sanitize(modFile.getModFileInfo().moduleName());
        String version = sanitize(modFile.getModFileInfo().versionString());
        Path path = modFile.getFilePath().toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            if (verifyJarHash) {
                return module + '-' + version + "-sha256-" + JAR_HASHES.computeIfAbsent(path, ResourcePackCacheKey::jarHash);
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

    private static String jarHash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}

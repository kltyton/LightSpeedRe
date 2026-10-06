package com.ccr4ft3r.lightspeed.cache.resource;

import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ResourcePackCacheKey {
    private static final ConcurrentMap<Path, String> JAR_HASHES = new ConcurrentHashMap<>();

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
            return identity + "-sha256-" + JAR_HASHES.computeIfAbsent(path, ResourcePackCacheKey::jarHash);
        } catch (UncheckedIOException exception) {
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
}

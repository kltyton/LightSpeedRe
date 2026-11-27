package com.ccr4ft3r.lightspeed.startup.bootstrap.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Compact binary persistence for {@link EarlyZipResourceIndex} keyed by normalized path,
 * file size, and last-modified timestamp so warm launches skip re-reading untouched ZIP central directories.
 */
final class EarlyZipIndexStore {
    private static final int MAGIC = 0x4C535A32;
    private static final int VERSION = 2;
    private static final String CACHE_RELATIVE_PATH = "lightspeed-cache/early-zip-index-v2.bin";
    private static final String TMP_RELATIVE_PATH = "lightspeed-cache/early-zip-index-v2.bin.tmp";

    record CachedArchive(long size, long mtimeMillis, String[] entries) {
    }

    private EarlyZipIndexStore() {
    }

    static Path cachePath(Path instanceDirectory) {
        return instanceDirectory.resolve(CACHE_RELATIVE_PATH);
    }

    static Map<String, CachedArchive> load(Path instanceDirectory) {
        Path file = cachePath(instanceDirectory);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 65536))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return Map.of();
            }
            int archiveCount = in.readInt();
            if (archiveCount <= 0 || archiveCount > 65536) {
                return Map.of();
            }
            Map<String, CachedArchive> result = new HashMap<>(archiveCount * 4 / 3 + 1);
            byte[] scratch = new byte[4096];
            for (int i = 0; i < archiveCount; i++) {
                String key = readString(in, scratch);
                long size = in.readLong();
                long mtimeMillis = in.readLong();
                int entryCount = in.readInt();
                if (entryCount < 0 || entryCount > 1_000_000) {
                    return Map.of();
                }
                String[] entries = entryCount == 0 ? new String[0] : new String[entryCount];
                for (int j = 0; j < entryCount; j++) {
                    entries[j] = readString(in, scratch);
                }
                result.put(key, new CachedArchive(size, mtimeMillis, entries));
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    static void save(Path instanceDirectory, Map<String, CachedArchive> snapshot) {
        if (instanceDirectory == null || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        Path target = cachePath(instanceDirectory);
        Path tmp = instanceDirectory.resolve(TMP_RELATIVE_PATH);
        try {
            Files.createDirectories(target.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 65536))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(snapshot.size());
                for (Map.Entry<String, CachedArchive> archiveEntry : snapshot.entrySet()) {
                    CachedArchive archive = archiveEntry.getValue();
                    if (archive == null || archive.entries() == null) {
                        continue;
                    }
                    writeString(out, archiveEntry.getKey());
                    out.writeLong(archive.size());
                    out.writeLong(archive.mtimeMillis());
                    String[] entries = archive.entries();
                    out.writeInt(entries.length);
                    for (String entry : entries) {
                        writeString(out, entry);
                    }
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignoredDelete) {
            }
        }
    }

    private static String readString(DataInputStream in, byte[] scratch) throws IOException {
        int len = in.readInt();
        if (len == 0) {
            return "";
        }
        if (len < 0 || len > 1_048_576) {
            throw new IOException("Corrupt string length in early-zip index: " + len);
        }
        if (len <= scratch.length) {
            in.readFully(scratch, 0, len);
            return new String(scratch, 0, len, StandardCharsets.UTF_8);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new IOException("Truncated early-zip index string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        if (bytes.length > 0) {
            out.write(bytes);
        }
    }
}

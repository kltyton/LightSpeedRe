package com.ccr4ft3r.lightspeed.cache.assets;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Versioned byte-entry store; every asset domain owns a separate instance and failure boundary. */
public final class SnapshotFileStore {
    private final Path file;
    private final int magic;
    private final int version;
    private final int maxEntries;
    private final int maxEntryBytes;
    private final long maxTotalBytes;
    private final ConcurrentHashMap<String, byte[]> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private volatile boolean loaded;
    private volatile boolean dirty;
    private long totalBytes;

    public SnapshotFileStore(Path file, int magic, int version, int maxEntries,
                             int maxEntryBytes, long maxTotalBytes) {
        this.file = file;
        this.magic = magic;
        this.version = version;
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public byte[] get(String key) {
        ensureLoaded();
        byte[] value = entries.get(key);
        if (value == null) {
            misses.increment();
            return null;
        }
        hits.increment();
        return value.clone();
    }

    public synchronized boolean put(String key, byte[] value) {
        ensureLoaded();
        if (key == null || key.isBlank() || value == null || value.length > maxEntryBytes) {
            return false;
        }
        byte[] previous = entries.get(key);
        long nextTotal = totalBytes - (previous == null ? 0 : previous.length) + value.length;
        if ((previous == null && entries.size() >= maxEntries) || nextTotal > maxTotalBytes) {
            return false;
        }
        entries.put(key, value.clone());
        totalBytes = nextTotal;
        dirty = true;
        return true;
    }

    public synchronized void persist() {
        ensureLoaded();
        if (!dirty) {
            return;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            failures.increment();
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(magic);
                output.writeInt(version);
                output.writeInt(entries.size());
                for (Map.Entry<String, byte[]> entry : entries.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    output.writeUTF(entry.getKey());
                    output.writeInt(entry.getValue().length);
                    output.write(entry.getValue());
                }
            }
            moveIntoPlace(temporary, file);
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            failures.increment();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    failures.increment();
                }
            }
        }
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long failures() {
        return failures.sum();
    }

    public int size() {
        ensureLoaded();
        return entries.size();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            load();
            loaded = true;
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        ConcurrentHashMap<String, byte[]> decoded = new ConcurrentHashMap<>();
        long decodedBytes = 0;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != magic || input.readInt() != version) {
                failures.increment();
                return;
            }
            int count = input.readInt();
            if (count < 0 || count > maxEntries) {
                throw new IOException("Invalid snapshot entry count " + count);
            }
            for (int index = 0; index < count; index++) {
                String key = input.readUTF();
                int length = input.readInt();
                if (key.isBlank() || length < 0 || length > maxEntryBytes
                        || decodedBytes + length > maxTotalBytes) {
                    throw new IOException("Invalid snapshot entry at index " + index);
                }
                decoded.put(key, input.readNBytes(length));
                if (decoded.get(key).length != length) {
                    throw new IOException("Truncated snapshot entry at index " + index);
                }
                decodedBytes += length;
            }
            entries.putAll(decoded);
            totalBytes = decodedBytes;
        } catch (IOException | RuntimeException exception) {
            entries.clear();
            totalBytes = 0;
            failures.increment();
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

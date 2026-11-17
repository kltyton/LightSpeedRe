package com.ccr4ft3r.lightspeed.startup.compat;

import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.startup.bootstrap.index.EarlyZipResourceIndex;
import com.ccr4ft3r.lightspeed.startup.compat.EarlyModPackIndexEngine.ModPackIndex;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists pre-validated {@link ModPackIndex} structures keyed by normalized archive path,
 * file size, and mtime. Filtered roots retain the raw catalog so their current filter is replayed.
 */
final class ModPackIndexStore {
    private static final int MAGIC = 0x4C534D31;
    private static final int VERSION = 2;
    private static final int MAX_RAW_ENTRIES = 1_000_000;
    private static final long MAX_RAW_BYTES = 128L * 1024L * 1024L;
    private static final String CACHE_RELATIVE_PATH = "lightspeed-cache/mod-pack-index-v1.bin";
    private static final String TMP_RELATIVE_PATH = "lightspeed-cache/mod-pack-index-v1.bin.tmp";

    private static final ConcurrentHashMap<String, StoredPack> ENTRIES = new ConcurrentHashMap<>(512);
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean PRELOAD_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static final AtomicBoolean FLUSHING = new AtomicBoolean(false);

    private record StoredPack(
            long size,
            long mtimeMillis,
            boolean hasFilter,
            ModPackIndex index,
            String[] rawEntries
    ) {
    }

    private ModPackIndexStore() {
    }

    static void preloadAsync() {
        if (LOADED.get() || !PRELOAD_STARTED.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            ensureLoaded();
            prewarmArchivesAsync();
        });
    }

    private static void prewarmArchivesAsync() {
        if (ENTRIES.isEmpty()) {
            return;
        }
        Set<Path> seen = new HashSet<>(ENTRIES.size() * 2);
        List<ModPackIndex> targets = new ArrayList<>(ENTRIES.size());
        for (StoredPack stored : ENTRIES.values()) {
            ModPackIndex idx = stored.index();
            if (idx != null && idx.archivePath != null && seen.add(idx.archivePath)) {
                targets.add(idx);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        int threads = Math.min(targets.size(), Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));
        AtomicInteger cursor = new AtomicInteger(0);
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                while (true) {
                    int index = cursor.getAndIncrement();
                    if (index >= targets.size()) {
                        return;
                    }
                    targets.get(index).prewarmArchive();
                }
            }, "Lightspeed-PackArchivePrewarm-" + (i + 1));
            worker.setDaemon(true);
            worker.start();
        }
    }

    static ModPackIndex tryGetUnfiltered(Path diskPath) {
        StoredPack stored = validPack(diskPath, false);
        return stored == null ? null : stored.index();
    }

    static String[] rawEntries(Path diskPath) {
        StoredPack stored = validPack(diskPath, true);
        return stored == null ? null : stored.rawEntries();
    }

    private static StoredPack validPack(Path diskPath, boolean hasFilter) {
        if (diskPath == null) {
            return null;
        }
        ensureLoaded();
        StoredPack stored = ENTRIES.get(key(diskPath, hasFilter));
        if (stored == null || stored.hasFilter() != hasFilter) {
            return null;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(diskPath, BasicFileAttributes.class);
            return attrs.size() == stored.size() && attrs.lastModifiedTime().toMillis() == stored.mtimeMillis()
                    ? stored : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    static void record(Path diskPath, boolean hasFilter, ModPackIndex index, String[] rawEntries) {
        if (diskPath == null || index == null) {
            return;
        }
        ensureLoaded();
        try {
            BasicFileAttributes attrs = Files.readAttributes(diskPath, BasicFileAttributes.class);
            ENTRIES.put(
                    key(diskPath, hasFilter),
                    new StoredPack(attrs.size(), attrs.lastModifiedTime().toMillis(), hasFilter, index,
                            boundedRawEntries(rawEntries)));
            DIRTY.set(true);
        } catch (IOException ignored) {
        }
    }

    static void flushAsync() {
        if (!DIRTY.get() || !FLUSHING.compareAndSet(false, true)) {
            return;
        }
        Path instanceDir = resolveInstanceDirectory();
        CompletableFuture.runAsync(() -> {
            try {
                if (DIRTY.compareAndSet(true, false)) {
                    save(instanceDir);
                }
            } finally {
                FLUSHING.set(false);
            }
        });
    }

    private static String key(Path diskPath, boolean hasFilter) {
        return EarlyZipResourceIndex.normalizeKey(diskPath) + '|' + (hasFilter ? '1' : '0');
    }

    private static Path resolveInstanceDirectory() {
        Path early = EarlyZipResourceIndex.instanceDirectory();
        return early != null ? early : Path.of(System.getProperty("user.dir", "."));
    }

    private static void ensureLoaded() {
        if (LOADED.get()) {
            return;
        }
        synchronized (ModPackIndexStore.class) {
            if (LOADED.get()) {
                return;
            }
            try {
                load(resolveInstanceDirectory());
            } finally {
                LOADED.set(true);
            }
        }
    }

    private static void load(Path instanceDir) {
        Path file = instanceDir.resolve(CACHE_RELATIVE_PATH);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 65536))) {
            if (in.readInt() != MAGIC) {
                return;
            }
            int version = in.readInt();
            if (version != 1 && version != VERSION) {
                return;
            }
            int packCount = in.readInt();
            if (packCount <= 0 || packCount > 65536) {
                return;
            }
            byte[] scratch = new byte[4096];
            for (int i = 0; i < packCount; i++) {
                String mapKey = readString(in, scratch);
                Path archivePath = Path.of(readString(in, scratch));
                long size = in.readLong();
                long mtimeMillis = in.readLong();
                boolean hasFilter = in.readBoolean();
                boolean hasServerEntries = in.readBoolean();
                Map<String, ResourcePathIndex> clientPaths = readPathMap(in, scratch);
                Map<String, ResourcePathIndex> serverPaths = readPathMap(in, scratch);
                ObjectOpenHashSet<String> rootFiles = readStringSet(in, scratch);
                ObjectOpenHashSet<String> dirPrefixes = readStringSet(in, scratch);
                String[] rawEntries = version >= 2 ? readRawEntries(in, scratch) : null;

                ModPackIndex index = new ModPackIndex(
                        archivePath,
                        clientPaths.isEmpty() ? Set.of() : Collections.unmodifiableSet(clientPaths.keySet()),
                        serverPaths.isEmpty() ? Set.of() : Collections.unmodifiableSet(serverPaths.keySet()),
                        hasServerEntries,
                        clientPaths,
                        serverPaths,
                        rootFiles,
                        dirPrefixes
                );
                ENTRIES.put(mapKey, new StoredPack(size, mtimeMillis, hasFilter, index, rawEntries));
            }
        } catch (IOException | RuntimeException ignored) {
            ENTRIES.clear();
        }
    }

    private static void save(Path instanceDir) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        Path target = instanceDir.resolve(CACHE_RELATIVE_PATH);
        Path tmp = instanceDir.resolve(TMP_RELATIVE_PATH);
        try {
            Files.createDirectories(target.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 65536))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(ENTRIES.size());
                for (Map.Entry<String, StoredPack> entry : ENTRIES.entrySet()) {
                    StoredPack stored = entry.getValue();
                    ModPackIndex idx = stored.index();
                    writeString(out, entry.getKey());
                    writeString(out, idx.archivePath.toString());
                    out.writeLong(stored.size());
                    out.writeLong(stored.mtimeMillis());
                    out.writeBoolean(stored.hasFilter());
                    out.writeBoolean(idx.hasServerEntries);
                    writePathMap(out, idx.clientPaths);
                    writePathMap(out, idx.serverPaths);
                    writeStringSet(out, idx.rootFiles);
                    writeStringSet(out, idx.directoryPrefixes);
                    writeRawEntries(out, stored.rawEntries());
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

    private static Map<String, ResourcePathIndex> readPathMap(DataInputStream in, byte[] scratch) throws IOException {
        int count = in.readInt();
        if (count == 0) {
            return Map.of();
        }
        if (count < 0 || count > 65536) {
            throw new IOException("Corrupt namespace count: " + count);
        }
        Map<String, ResourcePathIndex> map = new Object2ObjectOpenHashMap<>(count);
        for (int i = 0; i < count; i++) {
            String ns = readString(in, scratch);
            int pathCount = in.readInt();
            if (pathCount < 0 || pathCount > 1_000_000) {
                throw new IOException("Corrupt path count: " + pathCount);
            }
            String[] paths = new String[pathCount];
            for (int j = 0; j < pathCount; j++) {
                paths[j] = readString(in, scratch);
            }
            map.put(ns, ResourcePathIndex.fromPrevalidatedSorted(paths));
        }
        return Collections.unmodifiableMap(map);
    }

    private static String[] readRawEntries(DataInputStream in, byte[] scratch) throws IOException {
        int count = in.readInt();
        if (count == -1) {
            return null;
        }
        if (count < 0 || count > MAX_RAW_ENTRIES) {
            throw new IOException("Corrupt raw entry count: " + count);
        }
        String[] entries = new String[count];
        MessageDigest digest = rawDigest();
        long bytes = 0;
        for (int i = 0; i < count; i++) {
            entries[i] = readString(in, scratch);
            bytes += updateRawDigest(digest, entries[i]);
            if (bytes > MAX_RAW_BYTES) {
                throw new IOException("Raw entries exceed byte limit");
            }
        }
        byte[] expected = in.readNBytes(32);
        if (expected.length != 32 || !MessageDigest.isEqual(expected, digest.digest())) {
            throw new IOException("Raw entry checksum mismatch");
        }
        return entries;
    }

    private static void writeRawEntries(DataOutputStream out, String[] entries) throws IOException {
        out.writeInt(entries == null ? -1 : entries.length);
        if (entries != null) {
            MessageDigest digest = rawDigest();
            for (String entry : entries) {
                writeString(out, entry);
                updateRawDigest(digest, entry);
            }
            out.write(digest.digest());
        }
    }

    private static String[] boundedRawEntries(String[] entries) {
        if (entries == null || entries.length > MAX_RAW_ENTRIES) {
            return null;
        }
        long bytes = 0;
        for (String entry : entries) {
            if (entry == null) return null;
            int length = entry.getBytes(StandardCharsets.UTF_8).length;
            if (length > 1_048_576 || (bytes += length) > MAX_RAW_BYTES) return null;
        }
        return Arrays.copyOf(entries, entries.length);
    }

    private static MessageDigest rawDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int updateRawDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(bytes);
        return length;
    }

    private static void writePathMap(DataOutputStream out, Map<String, ResourcePathIndex> map) throws IOException {
        if (map == null || map.isEmpty()) {
            out.writeInt(0);
            return;
        }
        out.writeInt(map.size());
        for (Map.Entry<String, ResourcePathIndex> entry : map.entrySet()) {
            writeString(out, entry.getKey());
            String[] paths = entry.getValue().rawSortedPaths();
            out.writeInt(paths.length);
            for (String p : paths) {
                writeString(out, p);
            }
        }
    }

    private static ObjectOpenHashSet<String> readStringSet(DataInputStream in, byte[] scratch) throws IOException {
        int count = in.readInt();
        if (count <= 0) {
            return null;
        }
        if (count > 1_000_000) {
            throw new IOException("Corrupt set count: " + count);
        }
        ObjectOpenHashSet<String> set = new ObjectOpenHashSet<>(count);
        for (int i = 0; i < count; i++) {
            set.add(readString(in, scratch));
        }
        return set;
    }

    private static void writeStringSet(DataOutputStream out, ObjectOpenHashSet<String> set) throws IOException {
        if (set == null || set.isEmpty()) {
            out.writeInt(0);
            return;
        }
        out.writeInt(set.size());
        for (String value : set) {
            writeString(out, value);
        }
    }

    private static String readString(DataInputStream in, byte[] scratch) throws IOException {
        int len = in.readInt();
        if (len == 0) {
            return "";
        }
        if (len < 0 || len > 1_048_576) {
            throw new IOException("Corrupt string length: " + len);
        }
        if (len <= scratch.length) {
            in.readFully(scratch, 0, len);
            return new String(scratch, 0, len, StandardCharsets.UTF_8);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new IOException("Truncated mod-pack index string");
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

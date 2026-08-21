package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class StartupResourceImage {
    private static final int MAGIC = 0x4c535249;
    private static final int VERSION = 1;
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = Math.max(64, Math.min(1024,
            Integer.getInteger("lightspeed.resourceImageMiB", 512))) * 1024L * 1024L;
    private static final ConcurrentHashMap<String, byte[]> RECORDED = new ConcurrentHashMap<>();
    private static final AtomicLong RECORDED_BYTES = new AtomicLong();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final CompletableFuture<Map<String, byte[]>> LOADED = CompletableFuture.supplyAsync(
            StartupResourceImage::load, command -> {
                Thread thread = new Thread(command, "Lightspeed-Resource-Image-Load");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                thread.start();
            });

    private StartupResourceImage() {
    }

    public static void startLoading() {
        LOADED.isDone();
    }

    public static byte[] get(String key) {
        if (key == null) {
            return null;
        }
        byte[] recorded = RECORDED.get(key);
        if (recorded != null) {
            HITS.increment();
            return recorded;
        }
        try {
            byte[] loaded = LOADED.join().get(key);
            if (loaded == null) {
                MISSES.increment();
            } else {
                HITS.increment();
            }
            return loaded;
        } catch (RuntimeException exception) {
            FAILURES.increment();
            return null;
        }
    }

    public static void record(String key, byte[] bytes) {
        if (key == null || bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES
                || RECORDED.containsKey(key)) {
            return;
        }
        long total = RECORDED_BYTES.addAndGet(bytes.length);
        if (total > MAX_IMAGE_BYTES) {
            RECORDED_BYTES.addAndGet(-bytes.length);
            return;
        }
        byte[] previous = RECORDED.putIfAbsent(key, bytes);
        if (previous != null) {
            RECORDED_BYTES.addAndGet(-bytes.length);
        }
    }

    public static void persist() {
        if (RECORDED.isEmpty()) {
            return;
        }
        Map<String, byte[]> entries = new HashMap<>();
        try {
            entries.putAll(LOADED.join());
        } catch (RuntimeException exception) {
            FAILURES.increment();
        }
        entries.putAll(RECORDED);
        if (entries.isEmpty()) {
            return;
        }

        Path file = cacheFile();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(fingerprint());
                output.writeInt(entries.size());
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    output.writeUTF(entry.getKey());
                    output.writeInt(entry.getValue().length);
                    output.write(entry.getValue());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            FAILURES.increment();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                FAILURES.increment();
            }
        }
    }

    public static long hits() {
        return HITS.sum();
    }

    public static long misses() {
        return MISSES.sum();
    }

    public static long recordedBytes() {
        return RECORDED_BYTES.get();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    private static Map<String, byte[]> load() {
        Path file = cacheFile();
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || !fingerprint().equals(input.readUTF())) {
                return Map.of();
            }
            int count = input.readInt();
            if (count < 0 || count > 1_000_000) {
                throw new IOException("Invalid resource image entry count " + count);
            }
            Map<String, byte[]> entries = new HashMap<>(Math.max(16, count * 2));
            long total = 0;
            for (int index = 0; index < count; index++) {
                String key = input.readUTF();
                int length = input.readInt();
                total += length;
                if (length <= 0 || length > MAX_ENTRY_BYTES || total > MAX_IMAGE_BYTES) {
                    throw new IOException("Invalid resource image entry length " + length);
                }
                entries.put(key, input.readNBytes(length));
                if (entries.get(key).length != length) {
                    throw new IOException("Truncated resource image entry " + key);
                }
            }
            return Map.copyOf(entries);
        } catch (IOException | RuntimeException exception) {
            FAILURES.increment();
            return Map.of();
        }
    }

    private static Path cacheFile() {
        String configured = System.getProperty("lightspeed.bootstrapCacheDir");
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir", "."), "lightspeed-cache", "bootstrap")
                : Path.of(configured);
        return directory.resolve("resource-image-v1.bin");
    }

    private static String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path mods = Path.of(System.getProperty("user.dir", "."), "mods");
            if (Files.isDirectory(mods)) {
                try (var files = Files.list(mods)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                        String value = file.getFileName() + "\t" + Files.size(file) + "\t" + Files.getLastModifiedTime(file).toMillis();
                        digest.update(value.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException | RuntimeException exception) {
            FAILURES.increment();
            return "unavailable";
        }
    }
}

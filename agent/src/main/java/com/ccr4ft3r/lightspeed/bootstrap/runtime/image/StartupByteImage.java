package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

final class StartupByteImage {
    private static final int MAGIC = 0x4c534249;
    private static final int VERSION = 1;
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;

    private final Path file;
    private final String fingerprint;
    private final long maxBytes;
    private final String threadName;
    private final ConcurrentHashMap<String, byte[]> recorded = new ConcurrentHashMap<>();
    private final AtomicLong loadedBytes = new AtomicLong();
    private final AtomicLong recordedBytes = new AtomicLong();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private volatile CompletableFuture<Map<String, byte[]>> loaded;

    StartupByteImage(Path file, String fingerprint, long maxBytes, String threadName) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.maxBytes = maxBytes;
        this.threadName = threadName;
    }

    void start() {
        loadFuture();
    }

    byte[] get(String key) {
        if (key == null) {
            return null;
        }
        byte[] current = recorded.get(key);
        if (current == null) {
            try {
                current = loadFuture().join().get(key);
            } catch (RuntimeException exception) {
                failures.increment();
                return null;
            }
        }
        if (current == null) {
            misses.increment();
        } else {
            hits.increment();
        }
        return current;
    }

    void record(String key, byte[] bytes) {
        if (key == null || bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES
                || recorded.containsKey(key)) {
            return;
        }
        Map<String, byte[]> existing;
        try {
            existing = loadFuture().join();
        } catch (RuntimeException exception) {
            failures.increment();
            existing = Map.of();
        }
        if (existing.containsKey(key)) {
            return;
        }
        long total = recordedBytes.addAndGet(bytes.length);
        if (loadedBytes.get() + total > maxBytes) {
            recordedBytes.addAndGet(-bytes.length);
            return;
        }
        byte[] previous = recorded.putIfAbsent(key, bytes);
        if (previous != null) {
            recordedBytes.addAndGet(-bytes.length);
        }
    }

    void persist() {
        persist(ignored -> true);
    }

    void persist(Predicate<String> retain) {
        Map<String, byte[]> entries = new HashMap<>();
        Map<String, byte[]> existing;
        try {
            existing = loadFuture().join();
        } catch (RuntimeException exception) {
            failures.increment();
            existing = Map.of();
        }
        existing.forEach((key, value) -> {
            if (retain.test(key)) {
                entries.put(key, value);
            }
        });
        recorded.forEach((key, value) -> {
            if (retain.test(key)) {
                entries.put(key, value);
            }
        });
        if (recorded.isEmpty() && entries.size() == existing.size()) {
            return;
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(fingerprint);
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
            failures.increment();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failures.increment();
            }
        }
    }

    long hits() {
        return hits.sum();
    }

    long misses() {
        return misses.sum();
    }

    long recordedBytes() {
        return recordedBytes.get();
    }

    long failures() {
        return failures.sum();
    }

    private CompletableFuture<Map<String, byte[]>> loadFuture() {
        CompletableFuture<Map<String, byte[]>> current = loaded;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = loaded;
            if (current == null) {
                current = CompletableFuture.supplyAsync(this::load, command -> {
                    Thread thread = new Thread(command, threadName);
                    thread.setDaemon(true);
                    thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                    thread.start();
                });
                loaded = current;
            }
        }
        return current;
    }

    private Map<String, byte[]> load() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || !fingerprint.equals(input.readUTF())) {
                return Map.of();
            }
            int count = input.readInt();
            if (count < 0 || count > 1_000_000) {
                throw new IOException("invalid startup image entry count " + count);
            }
            Map<String, byte[]> entries = new HashMap<>(Math.max(16, count * 2));
            long total = 0;
            for (int index = 0; index < count; index++) {
                String key = input.readUTF();
                int length = input.readInt();
                total += length;
                if (length <= 0 || length > MAX_ENTRY_BYTES || total > maxBytes) {
                    throw new IOException("invalid startup image entry length " + length);
                }
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) {
                    throw new IOException("truncated startup image entry " + key);
                }
                entries.put(key, bytes);
            }
            loadedBytes.set(total);
            return Map.copyOf(entries);
        } catch (IOException | RuntimeException exception) {
            failures.increment();
            loadedBytes.set(0);
            return Map.of();
        }
    }
}

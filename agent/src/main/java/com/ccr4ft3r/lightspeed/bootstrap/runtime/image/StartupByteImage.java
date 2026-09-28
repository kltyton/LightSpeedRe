package com.ccr4ft3r.lightspeed.bootstrap.runtime.image;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

final class StartupByteImage {
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;

    private final PackImageFile packImage;
    private final long maxBytes;
    private final String threadName;
    private final ConcurrentHashMap<String, byte[]> recorded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeSegments = new ConcurrentHashMap<>();
    private final Set<String> invalidatedSegments = ConcurrentHashMap.newKeySet();
    private final AtomicLong loadedBytes = new AtomicLong();
    private final AtomicLong recordedBytes = new AtomicLong();
    private final LongAdder totalRecordedBytes = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private volatile CompletableFuture<PackImageFile.Image> loaded;

    StartupByteImage(Path file, String fingerprint, long maxBytes, String threadName) {
        this.packImage = new PackImageFile(file, fingerprint, maxBytes, MAX_ENTRY_BYTES);
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
        PackImageFile.Image existing;
        try {
            existing = loadFuture().join();
        } catch (RuntimeException exception) {
            failures.increment();
            return null;
        }
        activateSegment(key, existing);
        byte[] current = recorded.get(key);
        if (current == null && !isInvalidated(key)) {
            try {
                current = existing.get(key);
            } catch (IOException exception) {
                failures.increment();
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
        PackImageFile.Image existing;
        try {
            existing = loadFuture().join();
        } catch (RuntimeException exception) {
            failures.increment();
            existing = PackImageFile.Image.empty();
        }
        activateSegment(key, existing);
        if (!isInvalidated(key) && existing.containsKey(key)) {
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
        } else {
            totalRecordedBytes.add(bytes.length);
        }
    }

    void persist() {
        persist(ignored -> true);
    }

    synchronized void persist(Predicate<String> retain) {
        Map<String, byte[]> entries = new HashMap<>();
        Map<String, byte[]> recordedSnapshot = new HashMap<>(recorded);
        PackImageFile.Image existing;
        try {
            existing = loadFuture().join();
            existing.forEach((key, value) -> {
                if (retain.test(key) && !invalidatedSegments.contains(segment(key))) {
                    entries.put(key, value);
                }
            });
        } catch (IOException | RuntimeException exception) {
            failures.increment();
            existing = PackImageFile.Image.empty();
        }
        recordedSnapshot.forEach((key, value) -> {
            if (retain.test(key)) {
                entries.put(key, value);
            }
        });
        if (recordedSnapshot.isEmpty() && invalidatedSegments.isEmpty() && entries.size() == existing.size()) {
            return;
        }
        try {
            PackImageFile.Image persisted = packImage.write(entries);
            loaded = CompletableFuture.completedFuture(persisted);
            loadedBytes.set(persisted.dataBytes());
            invalidatedSegments.clear();
            recordedSnapshot.forEach((key, value) -> {
                if (recorded.remove(key, value)) {
                    recordedBytes.addAndGet(-value.length);
                }
            });
        } catch (IOException | RuntimeException exception) {
            failures.increment();
        }
    }

    long hits() {
        return hits.sum();
    }

    long misses() {
        return misses.sum();
    }

    long recordedBytes() {
        return totalRecordedBytes.sum();
    }

    long pendingBytes() {
        return recordedBytes.get();
    }

    long failures() {
        return failures.sum();
    }

    private CompletableFuture<PackImageFile.Image> loadFuture() {
        CompletableFuture<PackImageFile.Image> current = loaded;
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

    private PackImageFile.Image load() {
        try {
            PackImageFile.Image image = packImage.load();
            loadedBytes.set(image.dataBytes());
            image.forEachKey(this::registerLoadedSegment);
            return image;
        } catch (IOException | RuntimeException exception) {
            failures.increment();
            loadedBytes.set(0);
            return PackImageFile.Image.empty();
        }
    }

    private void activateSegment(String key, PackImageFile.Image existing) {
        String segment = segment(key);
        String locator = locator(segment);
        if (locator == null) {
            return;
        }
        String previous = activeSegments.put(locator, segment);
        if (previous == null || previous.equals(segment) || !invalidatedSegments.add(previous)) {
            return;
        }
        loadedBytes.addAndGet(-existing.segmentBytes(previous));
        removeRecordedSegment(previous);
    }

    private void registerLoadedSegment(String key) {
        String segment = segment(key);
        String locator = locator(segment);
        if (locator != null) {
            activeSegments.putIfAbsent(locator, segment);
        }
    }

    private void removeRecordedSegment(String segment) {
        String prefix = segment + '\0';
        recorded.forEach((key, value) -> {
            if (key.startsWith(prefix) && recorded.remove(key, value)) {
                recordedBytes.addAndGet(-value.length);
            }
        });
    }

    private boolean isInvalidated(String key) {
        String segment = segment(key);
        return segment != null && invalidatedSegments.contains(segment);
    }

    private static String segment(String key) {
        int separator = key.indexOf('\0');
        return separator > 0 ? key.substring(0, separator) : null;
    }

    private static String locator(String segment) {
        if (segment == null) {
            return null;
        }
        int separator = segment.lastIndexOf('\t');
        return separator > 0 ? segment.substring(0, separator) : null;
    }
}

package com.ccr4ft3r.lightspeed.startup.bootstrap.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Parallel central-directory pre-indexer running in Layer.SERVICE during early bootstrap.
 * Exposes only java.base structures via System properties so Layer.GAME can read them cleanly.
 */
public final class EarlyZipResourceIndex {
    public static final String PROPERTY_KEY = "lightspeed.earlyZipEntries.v1";
    public static final String READY_PROPERTY_KEY = "lightspeed.earlyZipEntries.ready.v1";

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int CD_ENTRY_SIG = 0x02014b50;
    private static final int UTF8_FLAG = 0x0800;
    private static final int MAX_EOCD_SEARCH = 22 + 65535;
    private static final int MAX_CD_BYTES = 128 * 1024 * 1024;
    private static final String[] EMPTY = new String[0];
    private static volatile Path instanceDirectory;
    private static volatile boolean released;

    private EarlyZipResourceIndex() {
    }

    public static Path instanceDirectory() {
        return instanceDirectory;
    }

    public static void clearSharedRegistry() {
        released = true;
        Object ready = System.getProperties().remove(READY_PROPERTY_KEY);
        if (ready instanceof CompletableFuture<?> future && !future.isDone()) {
            future.complete(null);
        }
        Object existing = System.getProperties().remove(PROPERTY_KEY);
        if (existing instanceof ConcurrentHashMap<?, ?> map) {
            map.clear();
        }
    }

    public static void preindexInstance(Path instanceDirectory) {
        if (instanceDirectory == null) {
            return;
        }
        released = false;
        EarlyZipResourceIndex.instanceDirectory = instanceDirectory;
        ConcurrentHashMap<String, CompletableFuture<String[]>> registry = sharedRegistry();
        CompletableFuture<Void> readyFuture = new CompletableFuture<>();
        System.getProperties().put(READY_PROPERTY_KEY, readyFuture);
        Thread initThread = new Thread(() -> runPreindex(instanceDirectory, registry, readyFuture),
                "Lightspeed-EarlyZipInit");
        initThread.setDaemon(true);
        initThread.start();
    }

    private static void runPreindex(
            Path instanceDirectory,
            ConcurrentHashMap<String, CompletableFuture<String[]>> registry,
            CompletableFuture<Void> readyFuture
    ) {
        ExecutorService executor = null;
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        ConcurrentHashMap<String, EarlyZipIndexStore.CachedArchive> nextSnapshot = null;
        AtomicBoolean dirty = new AtomicBoolean(false);
        try {
            if (released) {
                return;
            }
            List<Path> candidates = new ArrayList<>();
            collectArchives(instanceDirectory.resolve("mods"), true, candidates);
            collectArchives(instanceDirectory.resolve("resourcepacks"), false, candidates);
            if (candidates.isEmpty() || released) {
                return;
            }

            Map<String, EarlyZipIndexStore.CachedArchive> persisted = EarlyZipIndexStore.load(instanceDirectory);
            if (released) {
                return;
            }
            nextSnapshot = new ConcurrentHashMap<>(persisted);
            AtomicInteger threadId = null;

            for (Path archive : candidates) {
                if (released) {
                    break;
                }
                String key = normalizeKey(archive);
                CompletableFuture<String[]> entryFuture = new CompletableFuture<>();
                if (registry.putIfAbsent(key, entryFuture) != null) {
                    continue;
                }
                long fileSize = -1L;
                long mtimeMillis = 0L;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(archive, BasicFileAttributes.class);
                    fileSize = attrs.size();
                    mtimeMillis = attrs.lastModifiedTime().toMillis();
                } catch (IOException ignored) {
                }
                EarlyZipIndexStore.CachedArchive cached = persisted.get(key);
                if (cached != null && fileSize >= 0L && cached.size() == fileSize && cached.mtimeMillis() == mtimeMillis) {
                    entryFuture.complete(cached.entries());
                    continue;
                }
                if (executor == null) {
                    int threads = Math.min(12, Math.max(4, Runtime.getRuntime().availableProcessors()));
                    threadId = new AtomicInteger(1);
                    AtomicInteger ids = threadId;
                    executor = Executors.newFixedThreadPool(threads, runnable -> {
                        Thread thread = new Thread(runnable, "Lightspeed-EarlyZip-" + ids.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    });
                }
                long capturedSize = fileSize;
                long capturedMtime = mtimeMillis;
                ConcurrentHashMap<String, EarlyZipIndexStore.CachedArchive> snapshotRef = nextSnapshot;
                CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
                    try {
                        String[] entries = readNonClassEntries(archive);
                        entryFuture.complete(entries);
                        if (entries != null && capturedSize >= 0L) {
                            snapshotRef.put(key, new EarlyZipIndexStore.CachedArchive(capturedSize, capturedMtime, entries));
                            dirty.set(true);
                        }
                    } catch (Throwable ignored) {
                        entryFuture.complete(null);
                    }
                }, executor);
                pending.add(work);
            }
        } finally {
            readyFuture.complete(null);
        }

        if (pending.isEmpty()) {
            if (executor != null) {
                executor.shutdown();
            }
            return;
        }
        ExecutorService activeExecutor = executor;
        ConcurrentHashMap<String, EarlyZipIndexStore.CachedArchive> snapshotToSave = nextSnapshot;
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    try {
                        if (dirty.get() && snapshotToSave != null) {
                            EarlyZipIndexStore.save(instanceDirectory, snapshotToSave);
                        }
                    } finally {
                        activeExecutor.shutdown();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, CompletableFuture<String[]>> sharedRegistry() {
        Object existing = System.getProperties().get(PROPERTY_KEY);
        if (existing instanceof ConcurrentHashMap<?, ?> map) {
            return (ConcurrentHashMap<String, CompletableFuture<String[]>>) map;
        }
        ConcurrentHashMap<String, CompletableFuture<String[]>> created = new ConcurrentHashMap<>(512);
        Object raced = System.getProperties().putIfAbsent(PROPERTY_KEY, created);
        return raced instanceof ConcurrentHashMap<?, ?> map
                ? (ConcurrentHashMap<String, CompletableFuture<String[]>>) map
                : created;
    }

    private static void collectArchives(Path directory, boolean includeNestedVersionDirs, List<Path> out) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jar") || name.endsWith(".zip")) {
                        out.add(entry.toAbsolutePath().normalize());
                    }
                } else if (includeNestedVersionDirs && Files.isDirectory(entry)) {
                    collectArchives(entry, false, out);
                }
            }
        } catch (IOException ignored) {
            // Optional directory enumeration errors leave runtime discovery intact.
        }
    }

    public static String normalizeKey(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    public static String[] readNonClassEntries(Path archive) throws IOException {
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 22L) {
                return EMPTY;
            }
            int tailLen = (int) Math.min(fileSize, MAX_EOCD_SEARCH);
            long tailStart = fileSize - tailLen;
            ByteBuffer tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, tailStart, tail);

            int eocdRel = findEocd(tail, tailLen);
            if (eocdRel < 0) {
                return fallbackZipRead(archive);
            }

            int totalEntries16 = tail.getShort(eocdRel + 10) & 0xFFFF;
            long cdSize = Integer.toUnsignedLong(tail.getInt(eocdRel + 12));
            long cdOffset = Integer.toUnsignedLong(tail.getInt(eocdRel + 16));
            long entryHint = totalEntries16;

            if (totalEntries16 == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                long locatorPos = (tailStart + eocdRel) - 20L;
                if (locatorPos < 0L) {
                    return fallbackZipRead(archive);
                }
                ByteBuffer locBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, locatorPos, locBuf);
                if (locBuf.getInt(0) != ZIP64_LOCATOR_SIG) {
                    return fallbackZipRead(archive);
                }
                long zip64Offset = locBuf.getLong(8);
                if (zip64Offset < 0L || zip64Offset + 56L > fileSize) {
                    return fallbackZipRead(archive);
                }
                ByteBuffer zip64 = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, zip64Offset, zip64);
                if (zip64.getInt(0) != ZIP64_EOCD_SIG) {
                    return fallbackZipRead(archive);
                }
                entryHint = zip64.getLong(32);
                cdSize = zip64.getLong(40);
                cdOffset = zip64.getLong(48);
            }

            if (cdSize == 0L) {
                return EMPTY;
            }
            if (cdSize < 0L || cdSize > MAX_CD_BYTES || cdOffset < 0L || cdOffset + cdSize > fileSize) {
                return fallbackZipRead(archive);
            }

            byte[] cdBytes = new byte[(int) cdSize];
            readFully(channel, cdOffset, ByteBuffer.wrap(cdBytes));
            ByteBuffer cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN);
            int estimated = (int) Math.max(16L, Math.min(65536L, entryHint));
            List<String> names = new ArrayList<>(estimated);

            int pos = 0;
            while (pos + 46 <= cdBytes.length) {
                if (cdBuf.getInt(pos) != CD_ENTRY_SIG) {
                    return fallbackZipRead(archive);
                }
                int flags = cdBuf.getShort(pos + 8) & 0xFFFF;
                int nameLen = cdBuf.getShort(pos + 28) & 0xFFFF;
                int extraLen = cdBuf.getShort(pos + 30) & 0xFFFF;
                int commentLen = cdBuf.getShort(pos + 32) & 0xFFFF;
                int nameOff = pos + 46;
                pos = nameOff + nameLen + extraLen + commentLen;
                if (nameLen <= 0 || nameOff + nameLen > cdBytes.length) {
                    continue;
                }
                if (cdBytes[nameOff + nameLen - 1] == '/') {
                    continue;
                }
                if (endsWithDotClass(cdBytes, nameOff, nameLen)) {
                    continue;
                }
                Charset charset = (flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                String name = new String(cdBytes, nameOff, nameLen, charset);
                if (!name.isEmpty() && name.charAt(0) == '/') {
                    name = name.substring(1);
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return names.toArray(String[]::new);
        }
    }

    private static int findEocd(ByteBuffer tail, int tailLen) {
        for (int pos = tailLen - 22; pos >= 0; pos--) {
            if (tail.getInt(pos) == EOCD_SIG) {
                int commentLen = tail.getShort(pos + 20) & 0xFFFF;
                if (pos + 22 + commentLen == tailLen) {
                    return pos;
                }
            }
        }
        return -1;
    }

    private static boolean endsWithDotClass(byte[] buf, int off, int len) {
        return len >= 6
                && buf[off + len - 6] == '.'
                && buf[off + len - 5] == 'c'
                && buf[off + len - 4] == 'l'
                && buf[off + len - 3] == 'a'
                && buf[off + len - 2] == 's'
                && buf[off + len - 1] == 's';
    }

    private static void readFully(FileChannel channel, long position, ByteBuffer dst) throws IOException {
        long cursor = position;
        while (dst.hasRemaining()) {
            int read = channel.read(dst, cursor);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading zip index at " + cursor);
            }
            cursor += read;
        }
    }

    private static String[] fallbackZipRead(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && !name.endsWith(".class")) {
                    names.add(name.startsWith("/") ? name.substring(1) : name);
                }
            }
        }
        return names.toArray(String[]::new);
    }
}

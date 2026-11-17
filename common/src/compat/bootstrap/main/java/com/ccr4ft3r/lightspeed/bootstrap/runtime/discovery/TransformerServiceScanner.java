package com.ccr4ft3r.lightspeed.bootstrap.runtime.discovery;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.filesystem.ReadOnlyArchiveOpen;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.filesystem.StartupArchiveWindow;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class TransformerServiceScanner {
    private static final int MAGIC = 0x4C535453;
    private static final int VERSION = 2;
    private static final String CACHE_FILE_NAME = "transformer-services-v1.bin";
    private static final String TMP_FILE_NAME = "transformer-services-v1.bin.tmp";
    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_ENTRY_SIG = 0x02014b50;
    private static final int LOC_HEADER_SIG = 0x04034b50;
    private static final int UTF8_FLAG = 0x0800;
    private static final int MAX_EOCD_SEARCH = 22 + 65535;
    private static final int MAX_CD_BYTES = 64 * 1024 * 1024;
    private static final String VERSIONS_PREFIX = "META-INF/versions/";
    private static final String SERVICES_PREFIX = "META-INF/services/";
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String MODULE_INFO = "module-info.class";

    private static final Set<String> TRANSFORMER_SERVICES = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.minecraftforge.forgespi.locating.IModLocator",
            "net.minecraftforge.forgespi.locating.IDependencyLocator",
            "net.minecraftforge.fml.loading.ImmediateWindowProvider",
            "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "net.neoforged.neoforgespi.locating.IModFileReader",
            "net.neoforged.neoforgespi.locating.IDependencyLocator",
            "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
            "net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider");

    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final ConcurrentHashMap<String, CachedEntry> CACHE = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<String, Boolean> SESSION_VERIFIED = new ConcurrentHashMap<>(512);
    private static final Object PREWARM_LOCK = new Object();
    private static volatile boolean prewarmed;
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static volatile Path cacheFile;

    private record CachedEntry(long size, long mtimeMillis, boolean mayProvide) {
    }

    private TransformerServiceScanner() {
    }

    public static boolean mayProvide(Path path) {
        CANDIDATES.increment();
        if (path == null) {
            return true;
        }
        FileSystem fileSystem = path.getFileSystem();
        if (!"file".equalsIgnoreCase(fileSystem.provider().getScheme())) {
            return true;
        }
        String rawName = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!rawName.endsWith(".jar")) {
            return true;
        }

        Path normalized = path.toAbsolutePath().normalize();
        String key = normalizeKey(normalized);
        ensurePrewarmed(normalized);

        Boolean sessionResult = SESSION_VERIFIED.get(key);
        if (sessionResult != null) {
            if (!sessionResult) {
                REJECTED.increment();
            }
            return sessionResult;
        }

        BasicFileAttributes attrs = readRegularAttributes(normalized);
        if (attrs == null || attrs.size() <= 0L) {
            return true;
        }

        CachedEntry cached = CACHE.get(key);
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();
        if (cached != null && cached.size() == size && cached.mtimeMillis() == mtime) {
            SESSION_VERIFIED.put(key, cached.mayProvide());
            if (!cached.mayProvide()) {
                REJECTED.increment();
            }
            return cached.mayProvide();
        }

        boolean result = scanJar(normalized);
        CACHE.put(key, new CachedEntry(size, mtime, result));
        SESSION_VERIFIED.put(key, result);
        DIRTY.set(true);
        flushAsyncIfDirty();
        if (!result) {
            REJECTED.increment();
        }
        return result;
    }

    public static long candidates() {
        return CANDIDATES.sum();
    }

    public static long rejected() {
        return REJECTED.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    private static void ensurePrewarmed(Path sampleJar) {
        if (prewarmed) {
            return;
        }
        synchronized (PREWARM_LOCK) {
            if (prewarmed) {
                return;
            }
            try {
                Path resolvedCache = resolveCacheFile(sampleJar);
                cacheFile = resolvedCache;
                if (resolvedCache != null) {
                    loadCache(resolvedCache);
                }
                Path modsDir = sampleJar.getParent();
                if (modsDir == null || !Files.isDirectory(modsDir)) {
                    return;
                }
                List<Path> toScan = new ArrayList<>();
                List<Path> allJars = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
                    for (Path entry : stream) {
                        if (!entry.getFileName().toString().endsWith(".jar")) {
                            continue;
                        }
                        BasicFileAttributes attrs = readRegularAttributes(entry);
                        if (attrs == null || attrs.size() <= 0L) {
                            continue;
                        }
                        Path normalized = entry.toAbsolutePath().normalize();
                        allJars.add(normalized);
                        String key = normalizeKey(normalized);
                        CachedEntry cached = CACHE.get(key);
                        long size = attrs.size();
                        long mtime = attrs.lastModifiedTime().toMillis();
                        if (cached != null && cached.size() == size && cached.mtimeMillis() == mtime) {
                            SESSION_VERIFIED.put(key, cached.mayProvide());
                        } else {
                            toScan.add(normalized);
                        }
                    }
                } catch (IOException ignored) {
                    return;
                }
                if (!toScan.isEmpty()) {
                    scanParallel(toScan);
                    flushAsyncIfDirty();
                }
                if (StartupArchiveWindow.started() && allJars.size() >= 16) {
                    ReadOnlyArchiveOpen.preopenAsync(allJars);
                }
            } finally {
                prewarmed = true;
            }
        }
    }

    private static void scanParallel(List<Path> jars) {
        int threads = Math.min(jars.size(), Math.min(12, Math.max(2, Runtime.getRuntime().availableProcessors())));
        if (threads <= 1 || jars.size() <= 2) {
            for (Path jar : jars) {
                scanAndStore(jar);
            }
            return;
        }
        AtomicInteger ids = new AtomicInteger(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Lightspeed-ServiceScan-" + ids.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        try {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[jars.size()];
            for (int i = 0; i < jars.size(); i++) {
                Path jar = jars.get(i);
                futures[i] = CompletableFuture.runAsync(() -> scanAndStore(jar), pool);
            }
            CompletableFuture.allOf(futures).join();
        } finally {
            pool.shutdown();
        }
    }

    private static void scanAndStore(Path normalized) {
        BasicFileAttributes attrs = readRegularAttributes(normalized);
        if (attrs == null || attrs.size() <= 0L) {
            return;
        }
        String key = normalizeKey(normalized);
        boolean result = scanJar(normalized);
        CACHE.put(key, new CachedEntry(attrs.size(), attrs.lastModifiedTime().toMillis(), result));
        SESSION_VERIFIED.put(key, result);
        DIRTY.set(true);
    }

    private static boolean scanJar(Path path) {
        try {
            Boolean fast = scanCentralDirectory(path);
            if (fast != null) {
                return fast;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return scanWithJarFile(path);
    }

    private static Boolean scanCentralDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 22L) {
                return null;
            }
            int tailLen = (int) Math.min(fileSize, MAX_EOCD_SEARCH);
            long tailStart = fileSize - tailLen;
            ByteBuffer tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, tailStart, tail);
            int eocdRel = findEocd(tail, tailLen);
            if (eocdRel < 0) {
                return null;
            }
            int totalEntries16 = tail.getShort(eocdRel + 10) & 0xFFFF;
            long cdSize = Integer.toUnsignedLong(tail.getInt(eocdRel + 12));
            long cdOffset = Integer.toUnsignedLong(tail.getInt(eocdRel + 16));
            if (totalEntries16 == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                return null;
            }
            if (cdSize == 0L) {
                return Boolean.FALSE;
            }
            if (cdSize < 0L || cdSize > MAX_CD_BYTES || cdOffset < 0L || cdOffset + cdSize > fileSize) {
                return null;
            }

            byte[] cdBytes = new byte[(int) cdSize];
            readFully(channel, cdOffset, ByteBuffer.wrap(cdBytes));
            ByteBuffer cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN);

            int runtimeVersion = Runtime.version().feature();
            long manifestLocOffset = -1L;
            int manifestMethod = -1;
            int manifestCompSize = 0;
            int manifestUncompSize = 0;

            int pos = 0;
            while (pos + 46 <= cdBytes.length) {
                if (cdBuf.getInt(pos) != CD_ENTRY_SIG) {
                    return null;
                }
                int flags = cdBuf.getShort(pos + 8) & 0xFFFF;
                int method = cdBuf.getShort(pos + 10) & 0xFFFF;
                long compSize = Integer.toUnsignedLong(cdBuf.getInt(pos + 20));
                long uncompSize = Integer.toUnsignedLong(cdBuf.getInt(pos + 24));
                int nameLen = cdBuf.getShort(pos + 28) & 0xFFFF;
                int extraLen = cdBuf.getShort(pos + 30) & 0xFFFF;
                int commentLen = cdBuf.getShort(pos + 32) & 0xFFFF;
                long locOffset = Integer.toUnsignedLong(cdBuf.getInt(pos + 42));
                int nameOff = pos + 46;
                pos = nameOff + nameLen + extraLen + commentLen;
                if (nameLen <= 0 || nameOff + nameLen > cdBytes.length) {
                    continue;
                }
                if (nameLen == MODULE_INFO.length()
                        && matchesAscii(cdBytes, nameOff, MODULE_INFO)) {
                    return Boolean.TRUE;
                }
                if (nameLen > 9 && matchesPrefixAscii(cdBytes, nameOff, "META-INF/")) {
                    Charset charset = (flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                    String name = new String(cdBytes, nameOff, nameLen, charset);
                    if (name.startsWith(SERVICES_PREFIX)) {
                        String service = name.substring(SERVICES_PREFIX.length());
                        if (TRANSFORMER_SERVICES.contains(service)) {
                            return Boolean.TRUE;
                        }
                    } else if (name.startsWith(VERSIONS_PREFIX) && name.endsWith("/" + MODULE_INFO)) {
                        int versionEnd = name.indexOf('/', VERSIONS_PREFIX.length());
                        if (versionEnd < 0) {
                            return Boolean.TRUE;
                        }
                        try {
                            if (Integer.parseInt(name.substring(VERSIONS_PREFIX.length(), versionEnd)) <= runtimeVersion) {
                                return Boolean.TRUE;
                            }
                        } catch (NumberFormatException exception) {
                            return Boolean.TRUE;
                        }
                    } else if (MANIFEST_ENTRY.equals(name)) {
                        if (compSize > 1024 * 1024 || uncompSize > 4 * 1024 * 1024 || (flags & 1) != 0) {
                            return null;
                        }
                        manifestLocOffset = locOffset;
                        manifestMethod = method;
                        manifestCompSize = (int) compSize;
                        manifestUncompSize = (int) uncompSize;
                    }
                }
            }

            if (manifestLocOffset >= 0L) {
                byte[] manifestBytes = readEntryBytes(channel, fileSize, manifestLocOffset, manifestMethod,
                        manifestCompSize, manifestUncompSize);
                if (manifestBytes == null) {
                    return null;
                }
                Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
                if (Boolean.parseBoolean(manifest.getMainAttributes().getValue("Multi-Release"))) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    }

    private static byte[] readEntryBytes(FileChannel channel, long fileSize, long locOffset, int method,
                                         int compSize, int uncompSize) throws IOException {
        if (locOffset + 30L > fileSize) {
            return null;
        }
        ByteBuffer loc = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, locOffset, loc);
        if (loc.getInt(0) != LOC_HEADER_SIG) {
            return null;
        }
        int locNameLen = loc.getShort(26) & 0xFFFF;
        int locExtraLen = loc.getShort(28) & 0xFFFF;
        long dataOffset = locOffset + 30L + locNameLen + locExtraLen;
        if (dataOffset + compSize > fileSize) {
            return null;
        }
        byte[] raw = new byte[compSize];
        readFully(channel, dataOffset, ByteBuffer.wrap(raw));
        if (method == 0) {
            return raw;
        }
        if (method != 8) {
            return null;
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(raw);
            byte[] out = new byte[Math.max(uncompSize, 1)];
            int offset = 0;
            while (!inflater.finished()) {
                if (offset == out.length) {
                    byte[] grown = new byte[out.length << 1];
                    System.arraycopy(out, 0, grown, 0, out.length);
                    out = grown;
                }
                int count = inflater.inflate(out, offset, out.length - offset);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        return null;
                    }
                    break;
                }
                offset += count;
            }
            if (offset == out.length) {
                return out;
            }
            byte[] trimmed = new byte[offset];
            System.arraycopy(out, 0, trimmed, 0, offset);
            return trimmed;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static boolean scanWithJarFile(Path path) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (isMultiRelease(jar) || jar.getJarEntry(MODULE_INFO) != null || hasVersionedModuleDescriptor(jar)) {
                return true;
            }
            for (String service : TRANSFORMER_SERVICES) {
                if (jar.getJarEntry(SERVICES_PREFIX + service) != null) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            FAILURES.increment();
            return true;
        }
    }

    private static BasicFileAttributes readRegularAttributes(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.isRegularFile() ? attrs : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Path resolveCacheFile(Path sampleJar) {
        String configured = System.getProperty("lightspeed.bootstrapCacheDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize().resolve(CACHE_FILE_NAME);
        }
        Path parent = sampleJar.getParent();
        if (parent != null && "mods".equalsIgnoreCase(String.valueOf(parent.getFileName()))) {
            Path instanceDir = parent.getParent();
            if (instanceDir != null) {
                return instanceDir.resolve("lightspeed-cache/bootstrap").resolve(CACHE_FILE_NAME);
            }
        }
        return null;
    }

    private static void loadCache(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 32768))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return;
            }
            int count = in.readInt();
            if (count <= 0 || count > 65536) {
                return;
            }
            byte[] scratch = new byte[512];
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                if (len <= 0 || len > 16384) {
                    CACHE.clear();
                    return;
                }
                if (len > scratch.length) {
                    scratch = new byte[len];
                }
                in.readFully(scratch, 0, len);
                String key = new String(scratch, 0, len, StandardCharsets.UTF_8);
                long size = in.readLong();
                long mtime = in.readLong();
                boolean mayProvide = in.readBoolean();
                CACHE.put(key, new CachedEntry(size, mtime, mayProvide));
            }
        } catch (IOException | RuntimeException ignored) {
            CACHE.clear();
        }
    }

    private static void flushAsyncIfDirty() {
        Path target = cacheFile;
        if (target == null || !DIRTY.get()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            if (!DIRTY.compareAndSet(true, false)) {
                return;
            }
            Path tmp = target.resolveSibling(TMP_FILE_NAME);
            try {
                Files.createDirectories(target.getParent());
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 32768))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeInt(CACHE.size());
                    for (Map.Entry<String, CachedEntry> entry : CACHE.entrySet()) {
                        byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                        CachedEntry val = entry.getValue();
                        out.writeInt(keyBytes.length);
                        out.write(keyBytes);
                        out.writeLong(val.size());
                        out.writeLong(val.mtimeMillis());
                        out.writeBoolean(val.mayProvide());
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
        });
    }

    private static String normalizeKey(Path path) {
        return path.toString();
    }

    private static boolean matchesAscii(byte[] buf, int off, String target) {
        for (int i = 0; i < target.length(); i++) {
            if ((buf[off + i] & 0xFF) != target.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesPrefixAscii(byte[] buf, int off, String prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if ((buf[off + i] & 0xFF) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int findEocd(ByteBuffer tail, int tailLen) {
        for (int i = tailLen - 22; i >= 0; i--) {
            if (tail.getInt(i) == EOCD_SIG) {
                int commentLen = tail.getShort(i + 20) & 0xFFFF;
                if (i + 22 + commentLen == tailLen) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void readFully(FileChannel channel, long position, ByteBuffer dst) throws IOException {
        long pos = position;
        while (dst.hasRemaining()) {
            int n = channel.read(dst, pos);
            if (n < 0) {
                throw new IOException("Unexpected EOF reading archive at " + pos);
            }
            pos += n;
        }
    }

    private static boolean isMultiRelease(JarFile jar) throws IOException {
        return jar.getManifest() != null
                && Boolean.parseBoolean(jar.getManifest().getMainAttributes().getValue("Multi-Release"));
    }

    private static boolean hasVersionedModuleDescriptor(JarFile jar) {
        int runtimeVersion = Runtime.version().feature();
        return jar.stream().map(entry -> entry.getName()).anyMatch(name -> {
            if (!name.startsWith(VERSIONS_PREFIX) || !name.endsWith("/" + MODULE_INFO)) {
                return false;
            }
            int versionEnd = name.indexOf('/', VERSIONS_PREFIX.length());
            if (versionEnd < 0) {
                return true;
            }
            try {
                return Integer.parseInt(name.substring(VERSIONS_PREFIX.length(), versionEnd)) <= runtimeVersion;
            } catch (NumberFormatException exception) {
                return true;
            }
        });
    }
}

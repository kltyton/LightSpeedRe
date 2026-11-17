package com.ccr4ft3r.lightspeed.startup.compat;

import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.startup.bootstrap.index.EarlyZipResourceIndex;
import cpw.mods.niofs.union.UnionFileSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.packs.PackType;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared loader-neutral ZIP central-directory pack indexer and parallel task runner backed by
 * {@link EarlyZipResourceIndex}.
 */
public final class EarlyModPackIndexEngine {
    private EarlyModPackIndexEngine() {
    }

    /** Immutable per-mod-archive index partitioned once from {@link EarlyZipResourceIndex}. */
    public static final class ModPackIndex {
        final Path archivePath;
        final Set<String> clientNamespaces;
        final Set<String> serverNamespaces;
        final boolean hasServerEntries;
        final Map<String, ResourcePathIndex> clientPaths;
        final Map<String, ResourcePathIndex> serverPaths;
        final ObjectOpenHashSet<String> rootFiles;
        final ObjectOpenHashSet<String> directoryPrefixes;
        private volatile SharedZipHandle zipHandle;
        private volatile LockFreeArchive channelArchive;
        private volatile boolean channelUnavailable;

        ModPackIndex(
                Path archivePath,
                Set<String> clientNamespaces,
                Set<String> serverNamespaces,
                boolean hasServerEntries,
                Map<String, ResourcePathIndex> clientPaths,
                Map<String, ResourcePathIndex> serverPaths,
                ObjectOpenHashSet<String> rootFiles,
                ObjectOpenHashSet<String> directoryPrefixes
        ) {
            this.archivePath = archivePath;
            this.clientNamespaces = clientNamespaces;
            this.serverNamespaces = serverNamespaces;
            this.hasServerEntries = hasServerEntries;
            this.clientPaths = clientPaths;
            this.serverPaths = serverPaths;
            this.rootFiles = rootFiles;
            this.directoryPrefixes = directoryPrefixes;
        }

        public Set<String> namespaces(PackType type) {
            if (type == PackType.CLIENT_RESOURCES) {
                return clientNamespaces;
            }
            if (type == PackType.SERVER_DATA) {
                return hasServerEntries ? serverNamespaces : null;
            }
            return null;
        }

        public boolean contains(PackType type, String namespace, String relativePath) {
            Map<String, ResourcePathIndex> byNs = type == PackType.CLIENT_RESOURCES ? clientPaths
                    : type == PackType.SERVER_DATA ? serverPaths : null;
            if (byNs == null || byNs.isEmpty()) {
                return false;
            }
            ResourcePathIndex index = byNs.get(namespace);
            return index != null && index.contains(relativePath);
        }

        public ResourcePathIndex pathIndex(PackType type, String namespace) {
            Map<String, ResourcePathIndex> byNs = type == PackType.CLIENT_RESOURCES ? clientPaths
                    : type == PackType.SERVER_DATA ? serverPaths : null;
            return byNs == null ? null : byNs.get(namespace);
        }

        public InputStream openEntryStream(String entryName) throws IOException {
            if (archivePath == null || entryName == null || entryName.isEmpty()) {
                return null;
            }
            LockFreeArchive archive = acquireLockFreeArchive();
            if (archive != null) {
                try {
                    EntryLocation loc = archive.entries.get(entryName);
                    if (loc == null) {
                        return null;
                    }
                    if (loc.uncompSize >= 0 && loc.uncompSize <= MAX_LOCKFREE_ENTRY_BYTES
                            && loc.compSize >= 0 && loc.compSize <= MAX_LOCKFREE_ENTRY_BYTES
                            && (loc.method == 0 || loc.method == 8)) {
                        byte[] data = archive.readEntryBytes(loc);
                        if (data != null) {
                            return new ByteArrayInputStream(data);
                        }
                    }
                } finally {
                    archive.release();
                }
            }
            SharedZipHandle handle = acquireZipHandle();
            if (handle == null) {
                return null;
            }
            boolean transferred = false;
            try {
                ZipEntry entry = handle.zip.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    return null;
                }
                InputStream stream = handle.zip.getInputStream(entry);
                if (stream == null) {
                    return null;
                }
                InputStream wrapped = new TrackedZipEntryStream(stream, handle);
                transferred = true;
                return wrapped;
            } finally {
                if (!transferred) {
                    handle.release();
                }
            }
        }

        public void closeArchive() {
            LockFreeArchive archive = this.channelArchive;
            if (archive != null) {
                this.channelArchive = null;
                archive.retire();
            }
            SharedZipHandle handle = this.zipHandle;
            if (handle != null) {
                this.zipHandle = null;
                handle.retire();
            }
        }

        void prewarmArchive() {
            if (channelUnavailable || this.channelArchive != null || archivePath == null) {
                return;
            }
            LockFreeArchive archive = acquireLockFreeArchive();
            if (archive != null) {
                archive.release();
            }
        }

        private LockFreeArchive acquireLockFreeArchive() {
            if (channelUnavailable) {
                return null;
            }
            LockFreeArchive current = this.channelArchive;
            if (current != null && current.tryAcquire()) {
                return current;
            }
            synchronized (this) {
                if (channelUnavailable) {
                    return null;
                }
                current = this.channelArchive;
                if (current != null && current.tryAcquire()) {
                    return current;
                }
                try {
                    LockFreeArchive opened = LockFreeArchive.open(archivePath);
                    if (opened == null) {
                        channelUnavailable = true;
                        return null;
                    }
                    if (opened.tryAcquire()) {
                        this.channelArchive = opened;
                        return opened;
                    }
                    opened.retire();
                } catch (IOException | RuntimeException ignored) {
                    channelUnavailable = true;
                }
                return null;
            }
        }

        private SharedZipHandle acquireZipHandle() throws IOException {
            for (int attempt = 0; attempt < 2; attempt++) {
                SharedZipHandle current = this.zipHandle;
                if (current != null && current.tryAcquire()) {
                    return current;
                }
                synchronized (this) {
                    current = this.zipHandle;
                    if (current != null && current.tryAcquire()) {
                        return current;
                    }
                    SharedZipHandle opened = new SharedZipHandle(new ZipFile(archivePath.toFile()));
                    if (opened.tryAcquire()) {
                        this.zipHandle = opened;
                        return opened;
                    }
                    opened.retire();
                }
            }
            return null;
        }

        /**
         * Resolves a root path probe: {@code 1} known regular file, {@code 0} known absent,
         * {@code -1} unknown (directory prefix or non-standard path segments).
         */
        public int checkRoot(String[] parts) {
            if (parts == null || parts.length == 0) {
                return -1;
            }
            for (String part : parts) {
                if (part == null || part.isEmpty() || ".".equals(part) || "..".equals(part)
                        || part.indexOf('/') >= 0 || part.indexOf('\\') >= 0) {
                    return -1;
                }
            }
            if (parts.length >= 3) {
                String head = parts[0];
                PackType type = "assets".equals(head) ? PackType.CLIENT_RESOURCES
                        : "data".equals(head) ? PackType.SERVER_DATA : null;
                if (type != null) {
                    String rel = joinSubpath(parts, 2);
                    if (contains(type, parts[1], rel)) {
                        return 1;
                    }
                    String full = joinSubpath(parts, 0);
                    if (rootFiles != null && rootFiles.contains(full)) {
                        return 1;
                    }
                    return directoryPrefixes != null && directoryPrefixes.contains(full) ? -1 : 0;
                }
            }
            String full = joinSubpath(parts, 0);
            if (rootFiles != null && rootFiles.contains(full)) {
                return 1;
            }
            return directoryPrefixes != null && directoryPrefixes.contains(full) ? -1 : 0;
        }
    }

    private static final int MAX_LOCKFREE_ENTRY_BYTES = 16 * 1024 * 1024;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_ENTRY_SIG = 0x02014b50;
    private static final int LOC_HEADER_SIG = 0x04034b50;
    private static final int UTF8_FLAG = 0x0800;
    private static final int MAX_EOCD_SEARCH = 22 + 65535;
    private static final int MAX_CD_BYTES = 64 * 1024 * 1024;
    private static final ThreadLocal<Inflater> INFLATER_LOCAL = ThreadLocal.withInitial(() -> new Inflater(true));

    private record EntryLocation(long locOffset, int compSize, int uncompSize, short method) {
    }

    private static final class LockFreeArchive {
        private final FileChannel channel;
        private final long fileSize;
        private final Object2ObjectOpenHashMap<String, EntryLocation> entries;
        private final AtomicInteger state = new AtomicInteger(0); // bit 31 = retired, low bits = active reads
        private static final int RETIRED_BIT = 0x40000000;

        private LockFreeArchive(FileChannel channel, long fileSize, Object2ObjectOpenHashMap<String, EntryLocation> entries) {
            this.channel = channel;
            this.fileSize = fileSize;
            this.entries = entries;
        }

        private static LockFreeArchive open(Path path) throws IOException {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            boolean success = false;
            try {
                long fileSize = channel.size();
                if (fileSize < 22L) {
                    return null;
                }
                int tailLen = (int) Math.min(fileSize, MAX_EOCD_SEARCH);
                long tailStart = fileSize - tailLen;
                ByteBuffer tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN);
                readChannelFully(channel, tailStart, tail);
                int eocdRel = -1;
                for (int i = tailLen - 22; i >= 0; i--) {
                    if (tail.getInt(i) == EOCD_SIG && (tail.getShort(i + 20) & 0xFFFF) + i + 22 == tailLen) {
                        eocdRel = i;
                        break;
                    }
                }
                if (eocdRel < 0) {
                    return null;
                }
                int totalEntries16 = tail.getShort(eocdRel + 10) & 0xFFFF;
                long cdSize = Integer.toUnsignedLong(tail.getInt(eocdRel + 12));
                long cdOffset = Integer.toUnsignedLong(tail.getInt(eocdRel + 16));
                if (totalEntries16 == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                    return null;
                }
                if (cdSize < 0L || cdSize > MAX_CD_BYTES || cdOffset < 0L || cdOffset + cdSize > fileSize) {
                    return null;
                }
                byte[] cdBytes = new byte[(int) cdSize];
                readChannelFully(channel, cdOffset, ByteBuffer.wrap(cdBytes));
                ByteBuffer cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN);
                Object2ObjectOpenHashMap<String, EntryLocation> map = new Object2ObjectOpenHashMap<>(Math.max(16, totalEntries16));
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
                    if (cdBytes[nameOff + nameLen - 1] == '/' || endsWithClass(cdBytes, nameOff, nameLen)) {
                        continue;
                    }
                    if ((flags & 1) != 0 || compSize > MAX_LOCKFREE_ENTRY_BYTES || uncompSize > MAX_LOCKFREE_ENTRY_BYTES) {
                        continue;
                    }
                    Charset charset = (flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                    String name = new String(cdBytes, nameOff, nameLen, charset);
                    map.put(name, new EntryLocation(locOffset, (int) compSize, (int) uncompSize, (short) method));
                }
                success = true;
                return new LockFreeArchive(channel, fileSize, map);
            } finally {
                if (!success) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private static boolean endsWithClass(byte[] buf, int off, int len) {
            return len > 6
                    && buf[off + len - 6] == '.'
                    && buf[off + len - 5] == 'c'
                    && buf[off + len - 4] == 'l'
                    && buf[off + len - 3] == 'a'
                    && buf[off + len - 2] == 's'
                    && buf[off + len - 1] == 's';
        }

        private boolean tryAcquire() {
            for (;;) {
                int s = state.get();
                if ((s & RETIRED_BIT) != 0) {
                    return false;
                }
                if (state.compareAndSet(s, s + 1)) {
                    return true;
                }
            }
        }

        private void release() {
            int after = state.decrementAndGet();
            if (after == RETIRED_BIT) {
                closeQuietly();
            }
        }

        private void retire() {
            for (;;) {
                int s = state.get();
                if ((s & RETIRED_BIT) != 0) {
                    return;
                }
                int next = s | RETIRED_BIT;
                if (state.compareAndSet(s, next)) {
                    if (next == RETIRED_BIT) {
                        closeQuietly();
                    }
                    return;
                }
            }
        }

        private byte[] readEntryBytes(EntryLocation loc) throws IOException {
            if (loc.locOffset + 30L > fileSize) {
                return null;
            }
            ByteBuffer locBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            readChannelFully(channel, loc.locOffset, locBuf);
            if (locBuf.getInt(0) != LOC_HEADER_SIG) {
                return null;
            }
            int locNameLen = locBuf.getShort(26) & 0xFFFF;
            int locExtraLen = locBuf.getShort(28) & 0xFFFF;
            long dataOffset = loc.locOffset + 30L + locNameLen + locExtraLen;
            if (dataOffset + loc.compSize > fileSize) {
                return null;
            }
            byte[] raw = new byte[loc.compSize];
            readChannelFully(channel, dataOffset, ByteBuffer.wrap(raw));
            if (loc.method == 0) {
                return raw;
            }
            if (loc.method != 8) {
                return null;
            }
            Inflater inflater = INFLATER_LOCAL.get();
            inflater.reset();
            inflater.setInput(raw);
            byte[] out = new byte[loc.uncompSize];
            int offset = 0;
            try {
                while (!inflater.finished() && offset < out.length) {
                    int n = inflater.inflate(out, offset, out.length - offset);
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) {
                            return null;
                        }
                        break;
                    }
                    offset += n;
                }
                if (offset != loc.uncompSize) {
                    return null;
                }
                return out;
            } catch (DataFormatException e) {
                return null;
            }
        }

        private static void readChannelFully(FileChannel ch, long pos, ByteBuffer dst) throws IOException {
            long p = pos;
            while (dst.hasRemaining()) {
                int n = ch.read(dst, p);
                if (n < 0) {
                    throw new IOException("Unexpected EOF at " + p);
                }
                p += n;
            }
        }

        private void closeQuietly() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class SharedZipHandle {
        private final ZipFile zip;
        private int activeStreams;
        private boolean retired;
        private boolean closed;

        private SharedZipHandle(ZipFile zip) {
            this.zip = zip;
        }

        private synchronized boolean tryAcquire() {
            if (retired || closed) {
                return false;
            }
            activeStreams++;
            return true;
        }

        private void release() {
            boolean shouldClose = false;
            synchronized (this) {
                if (activeStreams > 0) {
                    activeStreams--;
                }
                if (retired && !closed && activeStreams == 0) {
                    closed = true;
                    shouldClose = true;
                }
            }
            if (shouldClose) {
                closeQuietly();
            }
        }

        private void retire() {
            boolean shouldClose = false;
            synchronized (this) {
                retired = true;
                if (!closed && activeStreams == 0) {
                    closed = true;
                    shouldClose = true;
                }
            }
            if (shouldClose) {
                closeQuietly();
            }
        }

        private void closeQuietly() {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class TrackedZipEntryStream extends FilterInputStream {
        private final SharedZipHandle handle;
        private final AtomicBoolean released = new AtomicBoolean();

        private TrackedZipEntryStream(InputStream in, SharedZipHandle handle) {
            super(in);
            this.handle = handle;
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return in.readAllBytes();
        }

        @Override
        public byte[] readNBytes(int len) throws IOException {
            return in.readNBytes(len);
        }

        @Override
        public long transferTo(OutputStream out) throws IOException {
            return in.transferTo(out);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (released.compareAndSet(false, true)) {
                    handle.release();
                }
            }
        }
    }

    public static void preloadIndexStoreAsync() {
        ModPackIndexStore.preloadAsync();
    }

    public static void flushCacheAsync() {
        ModPackIndexStore.flushAsync();
    }

    public static ModPackIndex buildFromUnionRoot(Path diskPath, Predicate<String> fsFilter) {
        if (diskPath == null) {
            return null;
        }
        boolean hasFilter = fsFilter != null;
        if (!hasFilter) {
            ModPackIndex cached = ModPackIndexStore.tryGetUnfiltered(diskPath);
            if (cached != null) {
                return cached;
            }
        }
        String[] allEntries = hasFilter ? ModPackIndexStore.rawEntries(diskPath) : null;
        if (allEntries == null) {
            allEntries = lookupOrReadEntries(diskPath);
        }
        if (allEntries == null) {
            return null;
        }

        Map<String, List<String>> clientLists = new Object2ObjectOpenHashMap<>(4);
        Map<String, List<String>> serverLists = new Object2ObjectOpenHashMap<>(4);
        ObjectOpenHashSet<String> rootFiles = new ObjectOpenHashSet<>(8);
        ObjectOpenHashSet<String> dirPrefixes = new ObjectOpenHashSet<>(Math.min(1024, Math.max(16, allEntries.length >>> 2)));
        boolean hasServerEntries = false;
        String lastDir = null;

        for (String entry : allEntries) {
            if (fsFilter != null && !fsFilter.test(entry)) {
                continue;
            }
            int lastSlash = entry.lastIndexOf('/');
            if (lastSlash > 0) {
                if (lastDir == null || lastDir.length() != lastSlash
                        || !entry.regionMatches(0, lastDir, 0, lastSlash)) {
                    lastDir = addDirectoryPrefixes(dirPrefixes, entry, lastSlash);
                }
            }
            if (entry.startsWith("assets/")) {
                if (collectNamespacedEntry(entry, "assets/".length(), clientLists)) {
                    continue;
                }
            } else if (entry.startsWith("data/")) {
                hasServerEntries = true;
                if (collectNamespacedEntry(entry, "data/".length(), serverLists)) {
                    continue;
                }
            }
            rootFiles.add(entry);
        }

        Map<String, ResourcePathIndex> clientPaths = toIndexMap(clientLists);
        Map<String, ResourcePathIndex> serverPaths = toIndexMap(serverLists);
        ModPackIndex built = new ModPackIndex(
                diskPath,
                clientPaths.isEmpty() ? Set.of() : Collections.unmodifiableSet(clientPaths.keySet()),
                serverPaths.isEmpty() ? Set.of() : Collections.unmodifiableSet(serverPaths.keySet()),
                hasServerEntries,
                clientPaths,
                serverPaths,
                rootFiles.isEmpty() ? null : rootFiles,
                dirPrefixes.isEmpty() ? null : dirPrefixes
        );
        ModPackIndexStore.record(diskPath, hasFilter, built, hasFilter ? allEntries : null);
        return built;
    }

    public static String[][] extractSingleJarPack(
            Map<PackType, Path> rootPathsByType,
            Map<FileSystem, Path> knownSingleJarFilesystems,
            Function<UnionFileSystem, Predicate<String>> filterFactory
    ) {
        if (rootPathsByType == null || rootPathsByType.isEmpty()) {
            return null;
        }
        Path assetsRoot = rootPathsByType.get(PackType.CLIENT_RESOURCES);
        Path dataRoot = rootPathsByType.get(PackType.SERVER_DATA);
        Path sample = assetsRoot != null ? assetsRoot : dataRoot;
        if (sample == null || !(sample.getFileSystem() instanceof UnionFileSystem ufs)) {
            return null;
        }
        if (assetsRoot != null && dataRoot != null && assetsRoot.getFileSystem() != dataRoot.getFileSystem()) {
            return null;
        }

        Path primary = ufs.getPrimaryPath();
        if (primary == null || primary.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        Path singleJar = resolveSingleJarPath(ufs, primary, knownSingleJarFilesystems);
        if (singleJar == null) {
            return null;
        }

        String assetsPrefix = normalizeTypePrefix(assetsRoot);
        String dataPrefix = normalizeTypePrefix(dataRoot);
        if (assetsPrefix == null && dataPrefix == null) {
            return null;
        }

        Predicate<String> fsFilter = filterFactory == null ? null : filterFactory.apply(ufs);
        if (fsFilter == null && (assetsPrefix == null || "assets/".equals(assetsPrefix))
                && (dataPrefix == null || "data/".equals(dataPrefix))) {
            ModPackIndex cached = ModPackIndexStore.tryGetUnfiltered(singleJar);
            if (cached != null) {
                return new String[][]{
                        assetsPrefix != null ? flattenPackPaths(cached.clientPaths) : null,
                        dataPrefix != null ? flattenPackPaths(cached.serverPaths) : null
                };
            }
        }

        String[] allEntries = fsFilter != null ? ModPackIndexStore.rawEntries(singleJar) : null;
        if (allEntries == null) {
            allEntries = lookupOrReadEntries(singleJar);
        }
        if (allEntries == null) {
            return null;
        }

        List<String> assetsOut = assetsPrefix != null ? new ArrayList<>() : null;
        List<String> dataOut = dataPrefix != null ? new ArrayList<>() : null;

        for (String entry : allEntries) {
            boolean matchesAssets = assetsOut != null && entry.startsWith(assetsPrefix);
            boolean matchesData = !matchesAssets && dataOut != null && entry.startsWith(dataPrefix);
            if (!matchesAssets && !matchesData) {
                continue;
            }
            if (fsFilter != null && !fsFilter.test(entry)) {
                continue;
            }
            if (matchesAssets) {
                int start = assetsPrefix.length();
                if (isValidCachedRelativePath(entry, start, entry.length())) {
                    assetsOut.add(entry.substring(start));
                }
            } else {
                int start = dataPrefix.length();
                if (isValidCachedRelativePath(entry, start, entry.length())) {
                    dataOut.add(entry.substring(start));
                }
            }
        }

        return new String[][]{
                assetsOut != null ? assetsOut.toArray(String[]::new) : null,
                dataOut != null ? dataOut.toArray(String[]::new) : null
        };
    }

    private static String[] flattenPackPaths(Map<String, ResourcePathIndex> byNamespace) {
        if (byNamespace == null || byNamespace.isEmpty()) {
            return new String[0];
        }
        int total = 0;
        for (ResourcePathIndex idx : byNamespace.values()) {
            total += idx.size();
        }
        if (total == 0) {
            return new String[0];
        }
        String[] out = new String[total];
        int pos = 0;
        for (Map.Entry<String, ResourcePathIndex> entry : byNamespace.entrySet()) {
            String prefix = entry.getKey() + '/';
            for (String rel : entry.getValue().rawSortedPaths()) {
                out[pos++] = prefix.concat(rel);
            }
        }
        return out;
    }

    public static String[] splitResourcePathWithPrefix(String head0, String head1, String path) {
        if (path == null || path.isEmpty()) {
            return new String[]{head0, head1, ""};
        }
        int effectiveEnd = path.length();
        while (effectiveEnd > 0 && path.charAt(effectiveEnd - 1) == '/') {
            effectiveEnd--;
        }
        if (effectiveEnd == 0) {
            return new String[]{head0, head1};
        }
        int segments = 1;
        for (int i = 0; i < effectiveEnd; i++) {
            if (path.charAt(i) == '/') {
                segments++;
            }
        }
        String[] result = new String[segments + 2];
        result[0] = head0;
        result[1] = head1;
        int outIdx = 2;
        int start = 0;
        for (int i = 0; i < effectiveEnd; i++) {
            if (path.charAt(i) == '/') {
                result[outIdx++] = path.substring(start, i);
                start = i + 1;
            }
        }
        result[outIdx] = path.substring(start, effectiveEnd);
        return result;
    }

    public static void runGenerationTasks(List<Runnable> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int count = tasks.size();
        if (count <= 2) {
            for (int i = 0; i < count; i++) {
                tasks.get(i).run();
            }
            return;
        }
        int parallelScanEnd = count - 1;
        List<CompletableFuture<Void>> futures = new ArrayList<>(parallelScanEnd - 1);
        for (int i = 1; i < parallelScanEnd; i++) {
            futures.add(CompletableFuture.runAsync(tasks.get(i)));
        }
        tasks.get(0).run();
        for (int i = 0; i < futures.size(); i++) {
            futures.get(i).join();
        }
        tasks.get(count - 1).run();
    }

    private static Path resolveSingleJarPath(
            UnionFileSystem ufs,
            Path primary,
            Map<FileSystem, Path> knownMods
    ) {
        Path verifiedModJar = knownMods == null ? null : knownMods.get(ufs);
        if (verifiedModJar != null) {
            return verifiedModJar;
        }
        ConcurrentHashMap<String, CompletableFuture<String[]>> preindexed = sharedRegistry();
        if (preindexed != null) {
            String key = EarlyZipResourceIndex.normalizeKey(primary);
            if (preindexed.containsKey(key) && Files.isRegularFile(primary)) {
                return primary;
            }
        }
        return null;
    }

    private static String[] lookupOrReadEntries(Path jarPath) {
        ConcurrentHashMap<String, CompletableFuture<String[]>> preindexed = sharedRegistry();
        if (preindexed != null) {
            CompletableFuture<String[]> future = preindexed.get(EarlyZipResourceIndex.normalizeKey(jarPath));
            if (future != null) {
                return future.join();
            }
        }
        try {
            return EarlyZipResourceIndex.readNonClassEntries(jarPath);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, CompletableFuture<String[]>> sharedRegistry() {
        Object value = System.getProperties().get(EarlyZipResourceIndex.PROPERTY_KEY);
        if (value instanceof ConcurrentHashMap<?, ?> map) {
            return (ConcurrentHashMap<String, CompletableFuture<String[]>>) map;
        }
        return null;
    }

    private static boolean collectNamespacedEntry(String entry, int prefixLen, Map<String, List<String>> out) {
        int nsSlash = entry.indexOf('/', prefixLen);
        if (nsSlash <= prefixLen || nsSlash + 1 >= entry.length()) {
            return false;
        }
        if (!isValidResourceNamespace(entry, prefixLen, nsSlash)
                || !isValidCachedRelativePath(entry, nsSlash + 1, entry.length())) {
            return false;
        }
        String ns = entry.substring(prefixLen, nsSlash);
        String rel = entry.substring(nsSlash + 1);
        out.computeIfAbsent(ns, ignored -> new ArrayList<>()).add(rel);
        return true;
    }

    private static Map<String, ResourcePathIndex> toIndexMap(Map<String, List<String>> lists) {
        if (lists.isEmpty()) {
            return Map.of();
        }
        Map<String, ResourcePathIndex> result = new Object2ObjectOpenHashMap<>(lists.size());
        for (Map.Entry<String, List<String>> e : lists.entrySet()) {
            ResourcePathIndex idx = ResourcePathIndex.from(e.getValue());
            if (idx.size() > 0) {
                result.put(e.getKey(), idx);
            }
        }
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    private static String addDirectoryPrefixes(ObjectOpenHashSet<String> prefixes, String entry, int lastSlash) {
        String immediateDir = entry.substring(0, lastSlash);
        if (!prefixes.add(immediateDir)) {
            return immediateDir;
        }
        int slash = entry.lastIndexOf('/', lastSlash - 1);
        while (slash > 0 && prefixes.add(entry.substring(0, slash))) {
            slash = entry.lastIndexOf('/', slash - 1);
        }
        return immediateDir;
    }

    private static boolean isValidResourceNamespace(String ns, int start, int end) {
        boolean[] allowed = ResourceLocationLookupSupport.NAMESPACE_CHARS;
        for (int i = start; i < end; i++) {
            char c = ns.charAt(i);
            if (c >= 128 || !allowed[c]) {
                return false;
            }
        }
        return true;
    }

    private static String joinSubpath(String[] parts, int start) {
        int remaining = parts.length - start;
        if (remaining == 1) {
            return parts[start];
        }
        if (remaining == 2) {
            return parts[start] + '/' + parts[start + 1];
        }
        int totalLen = remaining - 1;
        for (int i = start; i < parts.length; i++) {
            totalLen += parts[i].length();
        }
        StringBuilder sb = new StringBuilder(totalLen);
        sb.append(parts[start]);
        for (int i = start + 1; i < parts.length; i++) {
            sb.append('/').append(parts[i]);
        }
        return sb.toString();
    }

    private static String normalizeTypePrefix(Path rootPath) {
        if (rootPath == null) {
            return null;
        }
        String raw = rootPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        while (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            return null;
        }
        return raw + '/';
    }

    private static boolean isValidCachedRelativePath(String rel) {
        return isValidCachedRelativePath(rel, 0, rel.length());
    }

    private static boolean isValidCachedRelativePath(String rel, int start, int end) {
        if (end <= start || rel.charAt(start) == '/' || rel.charAt(end - 1) == '/') {
            return false;
        }
        boolean[] allowed = ResourceLocationLookupSupport.PATH_CHARS;
        boolean prevSlash = false;
        for (int i = start; i < end; i++) {
            char c = rel.charAt(i);
            if (c == '/') {
                if (prevSlash) {
                    return false;
                }
                prevSlash = true;
            } else {
                prevSlash = false;
                if (c >= 128 || !allowed[c]) {
                    return false;
                }
            }
        }
        return true;
    }
}

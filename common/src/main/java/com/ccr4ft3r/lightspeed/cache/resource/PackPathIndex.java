package com.ccr4ft3r.lightspeed.cache.resource;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.BiPredicate;

/** Per-pack path snapshots; persisted values contain names and booleans, never runtime file handles. */
public final class PackPathIndex implements ICache {
    private final List<Path> roots;
    private final String identity;
    private volatile Map<String, Boolean> existence;
    private volatile Map<PackType, Map<String, List<String>>> paths;
    private final Map<String, Path> resolved = new ConcurrentHashMap<>();
    private final Map<String, OrderedResourcePathIndex> indexes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<String>>> scans = new ConcurrentHashMap<>();

    public PackPathIndex(String owner, List<Path> roots) {
        this.roots = List.copyOf(roots);
        identity = identity(owner, roots);
        if (identity != null) {
            GlobalCache.loadPersistedCachesAsync();
        }
        GlobalCache.add(this);
    }

    public void recordNamespaces(Map<PackType, Set<String>> values) {
        if (identity != null) {
            Map<PackType, Set<String>> target = GlobalCache.PERSISTED_NAMESPACES_BY_MOD.computeIfAbsent(
                    identity, ignored -> new ConcurrentHashMap<>());
            values.forEach((type, names) -> {
                if (GlobalCache.shouldCacheEmptyNamespaces || !names.isEmpty()) {
                    target.put(type, Set.copyOf(names));
                }
            });
        }
    }

    public static Map<PackType, Set<String>> cachedNamespaces(String owner, List<Path> roots) {
        String identity = identity(owner, roots);
        if (identity == null) {
            return null;
        }
        Map<PackType, Set<String>> cached = GlobalCache.PERSISTED_NAMESPACES_BY_MOD.get(identity);
        if (cached == null || cached.size() != PackType.values().length) {
            return null;
        }
        Map<PackType, Set<String>> copy = new EnumMap<>(PackType.class);
        for (PackType type : PackType.values()) {
            Object encoded = ((Map<?, ?>) cached).get(type);
            if (!(encoded instanceof Set<?> names)) {
                cached.remove(type);
                LogUtils.getLogger().warn("Discarding malformed namespace snapshot {} for {}", identity, type);
                return null;
            }
            Set<String> decoded = new HashSet<>();
            for (Object name : names) {
                if (!(name instanceof String string)) {
                    cached.remove(type);
                    LogUtils.getLogger().warn("Discarding malformed namespace snapshot {} for {}", identity, type);
                    return null;
                }
                decoded.add(string);
            }
            copy.put(type, decoded);
        }
        return copy;
    }

    private Map<String, Boolean> existence() {
        Map<String, Boolean> current = existence;
        if (current == null) {
            synchronized (this) {
                current = existence;
                if (current == null) {
                    current = identity == null ? new ConcurrentHashMap<>()
                            : GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(identity,
                                    ignored -> new ConcurrentHashMap<>());
                    existence = current;
                    if (identity != null) {
                        GlobalCache.loadPersistedCacheAsync(
                                com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles.HAS_RESOURCE_CACHE_DIR,
                                identity, current);
                    }
                }
            }
        }
        return current;
    }

    /** Null means the original pack must resolve the path; an empty Optional is a known miss. */
    public Optional<Path> cachedPath(String filename) {
        Path known = resolved.get(filename);
        if (known != null) {
            return Optional.of(known);
        }
        if (GlobalCache.shouldCacheResourceExistence && Boolean.FALSE.equals(existence().get(filename))) {
            return Optional.empty();
        }
        return null;
    }

    public void recordPath(String filename, Path result) {
        if (result != null) {
            resolved.put(filename, result);
        }
        if (GlobalCache.shouldCacheResourceExistence) {
            existence().put(filename, result != null);
        }
    }

    public Boolean contains(String directory, String namespace, String path) {
        if (path.equals(".") || path.equals("..") || path.startsWith("./") || path.startsWith("../")
                || path.contains("/./") || path.contains("/../") || path.contains("//")
                || path.endsWith("/.") || path.endsWith("/..") || path.startsWith("/")) {
            return null;
        }
        PackType type = switch (directory) {
            case "assets" -> PackType.CLIENT_RESOURCES;
            case "data" -> PackType.SERVER_DATA;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        for (int root = 0; root < roots.size(); root++) {
            OrderedResourcePathIndex index = loadedIndex(type, namespace, root);
            if (index == null) {
                return null;
            }
            if (index.contains(path) || index.contains(path + "/")) {
                return true;
            }
        }
        return identity == null ? null : false;
    }

    public boolean listResources(PackType type, String namespace, String directory,
            PackResources.ResourceOutput output, BiPredicate<Path, BasicFileAttributes> includeFile) {
        List<OrderedResourcePathIndex> selected = new ArrayList<>(roots.size());
        List<Path> namespaceRoots = new ArrayList<>(roots.size());
        List<String> requested = new ArrayList<>(roots.size());
        for (int root = 0; root < roots.size(); root++) {
            Path namespaceRoot = roots.get(root).resolve(type.getDirectory()).resolve(namespace);
            String separator = namespaceRoot.getFileSystem().getSeparator();
            Path requestedRoot = namespaceRoot.resolve(directory.replace("/", separator)).normalize();
            if (!requestedRoot.startsWith(namespaceRoot)) {
                return false;
            }
            OrderedResourcePathIndex index = index(type, namespace, root,
                    () -> ResourceTreeSnapshot.read(namespaceRoot, includeFile));
            if (index == null) {
                return false;
            }
            selected.add(index);
            namespaceRoots.add(namespaceRoot);
            requested.add(namespaceRoot.relativize(requestedRoot).toString().replace(separator, "/"));
        }
        int[] invalid = {0};
        String[] firstInvalid = {null};
        for (int root = 0; root < selected.size(); root++) {
            Path namespaceRoot = namespaceRoots.get(root);
            selected.get(root).forEachAtOrUnder(requested.get(root), relative -> {
                if (relative.endsWith("/")) {
                    return;
                }
                if (!ResourceOutputEmitter.emit(namespace, relative,
                        IoSupplier.create(namespaceRoot.resolve(relative)), output) && invalid[0]++ == 0) {
                    firstInvalid[0] = relative;
                }
            });
        }
        if (invalid[0] > 0) {
            LogUtils.getLogger().warn("Ignoring {} invalid resource paths in {}:{} (first: {})",
                    invalid[0], namespace, directory, firstInvalid[0]);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public OrderedResourcePathIndex loadedIndex(PackType type, String namespace, int root) {
        String name = root + "/" + namespace;
        String key = type.name() + "/" + name;
        OrderedResourcePathIndex ready = indexes.get(key);
        if (ready != null) {
            return ready;
        }
        Map<PackType, Map<String, List<String>>> current = paths;
        if (current == null && identity != null) {
            current = GlobalCache.PERSISTED_RESOURCE_LISTS_BY_MOD.get(identity);
            if (current != null) {
                paths = current;
            }
        }
        Object encodedNamespace = current == null ? null : ((Map<?, ?>) current).get(type);
        if (encodedNamespace == null) {
            return null;
        }
        if (!(encodedNamespace instanceof Map<?, ?> byNamespace)) {
            current.remove(type);
            LogUtils.getLogger().warn("Discarding malformed resource snapshot {} for {}", identity, type);
            return null;
        }
        Object encoded = byNamespace.get(name);
        if (encoded == null) {
            return null;
        }
        if (!(encoded instanceof List<?> entries) || entries.stream().anyMatch(value -> !(value instanceof String))) {
            byNamespace.remove(name);
            LogUtils.getLogger().warn("Discarding malformed resource snapshot {} for {}", identity, key);
            return null;
        }
        return indexes.computeIfAbsent(key, ignored -> OrderedResourcePathIndex.from((List<String>) entries));
    }

    private Map<PackType, Map<String, List<String>>> paths() {
        Map<PackType, Map<String, List<String>>> current = paths;
        if (current == null) {
            synchronized (this) {
                current = paths;
                if (current == null) {
                    current = identity == null ? new ConcurrentHashMap<>()
                            : GlobalCache.PERSISTED_RESOURCE_LISTS_BY_MOD.computeIfAbsent(identity,
                                    ignored -> new ConcurrentHashMap<>());
                    paths = current;
                }
            }
        }
        return current;
    }

    public void invalidate(PackType type, String namespace, int root) {
        String name = root + "/" + namespace;
        Map<String, List<String>> byNamespace = paths == null ? null : paths.get(type);
        if (byNamespace != null) {
            byNamespace.remove(name);
        }
        indexes.remove(type.name() + "/" + name);
        scans.remove(type.name() + "/" + name);
    }

    public OrderedResourcePathIndex index(PackType type, String namespace, int root, Supplier<List<String>> scan) {
        OrderedResourcePathIndex loaded = loadedIndex(type, namespace, root);
        if (loaded != null) {
            return loaded;
        }
        String name = root + "/" + namespace;
        String key = type.name() + "/" + name;
        CompletableFuture<List<String>> pending = scans.computeIfAbsent(key, ignored ->
                GlobalCache.supplyCacheAfterPersistedLoad("index resource paths " + key, () -> {
                    Map<String, List<String>> byNamespace = paths().computeIfAbsent(type,
                            unused -> new ConcurrentHashMap<>());
                    return byNamespace.computeIfAbsent(name, unused -> List.copyOf(scan.get()));
                }));
        return pending.isDone() && pending.getNow(null) != null ? loadedIndex(type, namespace, root) : null;
    }

    public static String identity(String owner, List<Path> roots) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        digest.update(owner.getBytes(StandardCharsets.UTF_8));
        for (Path root : roots) {
            URI uri = root.toUri();
            URI archive = uri;
            if ("jar".equals(uri.getScheme())) {
                String nested = uri.getSchemeSpecificPart();
                int separator = nested.indexOf("!/");
                if (separator < 0) {
                    return null;
                }
                String fileUri = nested.substring(0, separator);
                if (!fileUri.startsWith("file:")) {
                    return null;
                }
                try {
                    // A jar URI is opaque; its decoded file path needs hierarchical quoting.
                    archive = new URI("file", null, fileUri.substring("file:".length()), null);
                } catch (URISyntaxException exception) {
                    throw new IllegalArgumentException("Invalid resource pack archive path: " + root, exception);
                }
            }
            if (!"file".equals(archive.getScheme())) {
                return null;
            }
            Path file = Path.of(archive);
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) {
                    return null;
                }
                String fingerprint = uri + "\n" + attributes.size() + "\n" + attributes.lastModifiedTime().toMillis();
                digest.update((byte) 0);
                digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                if (GlobalCache.shouldVerifyJarHash) {
                    digest.update(JarContentHash.get(file).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException | UncheckedIOException exception) {
                LogUtils.getLogger().warn("Cannot fingerprint resource pack {}", file, exception);
                return null;
            }
        }
        return "pack-path-v1-" + HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        resolved.clear();
        indexes.clear();
        scans.clear();
        if (existence != null) {
            existence.clear();
        }
        if (paths != null) {
            paths.clear();
        }
    }
}

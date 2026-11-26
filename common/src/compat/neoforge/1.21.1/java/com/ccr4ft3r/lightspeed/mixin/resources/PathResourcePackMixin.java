package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.PathResourceCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceDirectoryScan;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackCacheKey;
import com.ccr4ft3r.lightspeed.cache.resource.OrderedResourcePathIndex;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.startup.compat.EarlyModPackIndexEngine.ModPackIndex;
import com.ccr4ft3r.lightspeed.startup.compat.PackResourcesCacheSupport;
import com.google.common.collect.Maps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.DataResult;
import net.minecraft.FileUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.ccr4ft3r.lightspeed.util.CacheUtil.HAS_RESOURCE_CACHE_DIR;
import static com.ccr4ft3r.lightspeed.util.CacheUtil.NAMESPACE_CACHE_DIR;
import static com.ccr4ft3r.lightspeed.util.CacheUtil.RESOURCE_LIST_CACHE_DIR;

/** Caches resource discovery and summarizes invalid filenames during cached and direct enumeration. */
@Mixin(value = PathPackResources.class)
public abstract class PathResourcePackMixin implements IPathResourcePack, IPackResources {
    @Unique private volatile PathResourceCache lightspeed$paths;
    @Unique private volatile Map<String, ResourceDirectoryScan> lightspeed$directoryScans;

    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Path root;

    @Unique
    private volatile boolean lightspeed$existenceCacheLoadRequested;

    @Unique
    private volatile ModPackIndex lightspeed$earlyModIndex;
    @Unique
    private IModFile lightspeed$modFile;
    @Unique
    private String lightspeed$id;
    @Unique
    private volatile CompletableFuture<Void> lightspeed$persistedCacheLoad;

    @Unique
    private PathResourceCache lightspeed$cache() {
        PathResourceCache cache = lightspeed$paths;
        if (cache == null) {
            synchronized (this) {
                cache = lightspeed$paths;
                if (cache == null) {
                    cache = new PathResourceCache();
                    lightspeed$paths = cache;
                }
            }
        }
        return cache;
    }

    @Unique
    private Map<String, ResourceDirectoryScan> lightspeed$scans() {
        Map<String, ResourceDirectoryScan> scans = lightspeed$directoryScans;
        if (scans == null) {
            synchronized (this) {
                scans = lightspeed$directoryScans;
                if (scans == null) {
                    scans = new ConcurrentHashMap<>();
                    lightspeed$directoryScans = scans;
                }
            }
        }
        return scans;
    }

    @Unique
    private CompletableFuture<Void> lightspeed$persistedLoad() {
        CompletableFuture<Void> load = lightspeed$persistedCacheLoad;
        return load != null ? load : CompletableFuture.completedFuture(null);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(PackLocationInfo location, Path source, CallbackInfo ci) {
        if (lightspeed$earlyModIndex == null) {
            lightspeed$earlyModIndex = PackResourcesCacheSupport.tryGetModPackIndex(source);
        }
        lightspeed$cache();
        lightspeed$scans();
        if (GlobalCache.isEnabled) {
            lightspeed$resolvedPaths();
            lightspeed$namespaces();
            GlobalCache.add(this);
        }
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        ModPackIndex modIndex = lightspeed$earlyModIndex;
        if (modIndex != null && !FusionPackCompat.hasOverrides(this)) {
            Set<String> indexedNs = modIndex.namespaces(type);
            if (indexedNs != null) {
                cir.setReturnValue(indexedNs);
                return;
            }
        }
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this)) {
            return;
        }
        Set<String> namespaces = lightspeed$getCachedNamespaces(type);
        if (namespaces != null) {
            cir.setReturnValue(namespaces);
        }
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"))
    public void getNamespacesReturnInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this)) {
            return;
        }
        if (GlobalCache.shouldCacheEmptyNamespaces || cir.getReturnValue() != null && !cir.getReturnValue().isEmpty()) {
            lightspeed$cacheNamespaces(type, cir.getReturnValue());
        }
    }

    @Inject(method = "getRootResource", at = @At("HEAD"), cancellable = true)
    public void getRootResourceHeadInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ModPackIndex modIndex = lightspeed$earlyModIndex;
        if (modIndex != null && !FusionPackCompat.hasOverrides(this)) {
            int probe = modIndex.checkRoot(paths);
            if (probe == 0) {
                cir.setReturnValue(null);
                return;
            }
            if (probe > 0) {
                String[] copy = Arrays.copyOf(paths, paths.length);
                cir.setReturnValue(() -> lightspeed$openRootFromModIndex(modIndex, copy));
                return;
            }
        }
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence) {
            return;
        }

        String cacheKey = Arrays.toString(paths);
        Boolean exists = lightspeed$exists(cacheKey);
        if (exists != null) {
            cir.setReturnValue(exists ? lightspeed$openRootResource(Arrays.copyOf(paths, paths.length)) : null);
        }
    }

    @Inject(method = "getRootResource", at = @At("RETURN"))
    public void getRootResourceReturnInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence) {
            return;
        }

        lightspeed$cacheExists(Arrays.toString(paths), cir.getReturnValue() != null);
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType type, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ModPackIndex modIndex = lightspeed$earlyModIndex;
        if (modIndex != null && !FusionPackCompat.hasOverrides(this)) {
            if (!modIndex.contains(type, location.getNamespace(), location.getPath())) {
                cir.setReturnValue(null);
                return;
            }
            cir.setReturnValue(lightspeed$openResourceFromModIndex(modIndex, type, location));
            return;
        }
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return;
        }
        Boolean indexed = lightspeed$hasIndexedResource(type, location);
        if (indexed != null) {
            cir.setReturnValue(indexed ? lightspeed$openResource(type, location) : null);
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType type, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        ModPackIndex modIndex = lightspeed$earlyModIndex;
        if ((modIndex == null && (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths))
                || FusionPackCompat.hasOverrides(this)) {
            return;
        }

        List<String> parts = path.isEmpty() ? List.of() : FileUtil.decomposePath(path).result().orElse(null);
        if (parts == null) {
            return;
        }
        String requestedPath = String.join("/", parts);
        if (modIndex != null) {
            ResourcePathIndex pathIndex = modIndex.pathIndex(type, namespace);
            if (pathIndex != null) {
                pathIndex.forEachAtOrUnder(requestedPath, resourcePath -> {
                    ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
                    if (location != null) {
                        resourceOutput.accept(location, lightspeed$openResourceFromModIndex(modIndex, type, location));
                    }
                });
            }
            ci.cancel();
            return;
        }

        List<ResourceLocation> locations = new ArrayList<>();
        int[] invalidPathCount = {0};
        String[] firstInvalidPath = {null};
        try {
            Path namespaceRoot = lightspeed$resolve(type.getDirectory(), namespace).toAbsolutePath();
            List<String> cachedPaths = lightspeed$getCachedFilePaths(type, namespace);
            if (cachedPaths == null) {
                CompletableFuture<List<String>> scan = lightspeed$scheduleFilePathScan(type, namespace, namespaceRoot);
                if (scan == null) {
                    return;
                }
                cachedPaths = scan.getNow(null);
            }

            OrderedResourcePathIndex index;
            if (cachedPaths != null) {
                List<String> paths = cachedPaths;
                index = lightspeed$cache().orderedIndexes()
                        .computeIfAbsent(type, ignored -> Maps.newConcurrentMap())
                        .computeIfAbsent(namespace, ignored -> OrderedResourcePathIndex.from(paths));
            } else {
                ResourceDirectoryScan scan = lightspeed$scans().get(type.name() + "|" + namespace);
                List<String> selected = scan == null ? null : scan.scan(requestedPath);
                if (selected == null) {
                    return;
                }
                // A subtree snapshot must not populate the namespace index.
                index = OrderedResourcePathIndex.from(selected);
            }
            index.forEachAtOrUnder(requestedPath, resourcePath -> {
                ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
                if (location == null) {
                    if (invalidPathCount[0]++ == 0) {
                        firstInvalidPath[0] = resourcePath;
                    }
                } else {
                    locations.add(location);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Lightspeed path index lookup failed for {}:{}; falling back to vanilla resource enumeration",
                    namespace, path, e);
            return;
        }
        if (invalidPathCount[0] > 0) {
            LOGGER.warn("Ignoring {} invalid resource paths in {}:{} (first: {})",
                    invalidPathCount[0], namespace, path, firstInvalidPath[0]);
        }
        for (ResourceLocation location : locations) {
            resourceOutput.accept(location, lightspeed$openResource(type, location));
        }
        ci.cancel();
    }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/FileUtil;decomposePath(Ljava/lang/String;)Lcom/mojang/serialization/DataResult;"))
    private DataResult<List<String>> lightspeed$allowNamespaceRootListing(String path,
            Operation<DataResult<List<String>>> original) {
        return path.isEmpty() ? DataResult.success(List.of()) : original.call(path);
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$modFile == null || lightspeed$id == null) {
            lightspeed$getExistenceByResource().clear();
            lightspeed$namespaces().clear();
            lightspeed$relativeFilePaths().clear();
        }
        lightspeed$resolvedPaths().clear();
        lightspeed$resourcePathSets().clear();
        lightspeed$cache().orderedIndexes().clear();
        lightspeed$scanFutures().clear();
        lightspeed$scans().clear();
    }

    @WrapOperation(method = "listPath", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;forEach(Ljava/util/function/Consumer;)V"))
    private static void lightspeed$summarizeInvalidPaths(
            Stream<Path> paths, Consumer<Path> output, Operation<Void> original,
            @Local(argsOnly = true) String namespace, @Local(argsOnly = true) Path namespaceRoot) {
        int[] invalidCount = {0};
        String[] firstInvalidPath = {null};
        original.call(paths, (Consumer<Path>) candidate -> {
            String resourcePath = namespaceRoot.relativize(candidate).toString()
                    .replace(candidate.getFileSystem().getSeparator(), "/");
            if (ResourceLocation.tryBuild(namespace, resourcePath) == null) {
                if (invalidCount[0]++ == 0) {
                    firstInvalidPath[0] = resourcePath;
                }
            } else {
                output.accept(candidate);
            }
        });
        if (invalidCount[0] > 0) {
            LOGGER.warn("Ignoring {} invalid resource paths under {} (first: {}:{})",
                    invalidCount[0], namespaceRoot, namespace, firstInvalidPath[0]);
        }
    }

    @Override
    public void lightspeed$setModFile(IModFile modFile) {
        boolean sameFilesystem = PackResourcesCacheSupport.sharesModFileSystem(modFile, this.root);
        this.lightspeed$modFile = sameFilesystem ? modFile : null;
        if (sameFilesystem && this.lightspeed$earlyModIndex == null && this.root.toAbsolutePath().normalize().getNameCount() == 0) {
            this.lightspeed$earlyModIndex = PackResourcesCacheSupport.tryGetModPackIndex(modFile);
        }
        this.lightspeed$id = sameFilesystem && PackResourcesCacheSupport.hasSingleJarModRoot(modFile)
                ? ResourcePackCacheKey.from(modFile, GlobalCache.shouldVerifyJarHash) : null;
        lightspeed$persistedCacheLoad = CompletableFuture.completedFuture(null);
        if (lightspeed$id == null) {
            lightspeed$setExistenceByResource(Maps.newConcurrentMap());
            lightspeed$cache().bindNamespaces(Maps.newConcurrentMap());
            lightspeed$cache().bindLists(Maps.newConcurrentMap());
            return;
        }
        lightspeed$setExistenceByResource(GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$id, ignored -> Maps.newConcurrentMap()));
        lightspeed$cache().bindNamespaces(GlobalCache.PERSISTED_NAMESPACES_BY_MOD.computeIfAbsent(
                lightspeed$id, ignored -> Maps.newConcurrentMap()));
        lightspeed$cache().bindLists(GlobalCache.PERSISTED_RESOURCE_LISTS_BY_MOD.computeIfAbsent(
                lightspeed$id, ignored -> Maps.newConcurrentMap()));
        lightspeed$persistedCacheLoad = CompletableFuture.allOf(
                GlobalCache.loadPersistedCacheAsync(NAMESPACE_CACHE_DIR, lightspeed$id, lightspeed$namespaces()),
                GlobalCache.loadPersistedCacheAsync(RESOURCE_LIST_CACHE_DIR, lightspeed$id, lightspeed$relativeFilePaths()));
    }

    @Override
    public Boolean lightspeed$hasIndexedResource(PackType type, ResourceLocation location) {
        List<String> cachedPaths = lightspeed$getCachedFilePaths(type, location.getNamespace());
        if (cachedPaths == null) {
            return null;
        }
        Set<String> indexedPaths = lightspeed$getResourcePathSet(type, location.getNamespace(), cachedPaths);
        boolean present = indexedPaths.contains(location.getPath());
        // A Union directory can be a valid native lookup even though file enumeration omits it.
        return present ? Boolean.TRUE : lightspeed$id == null ? null : Boolean.FALSE;
    }

    @Unique
    public Path lightspeed$getResolvedPath(String... paths) {
        return lightspeed$cache().resolved(paths);
    }

    @Unique
    private Path lightspeed$resolve(String... paths) {
        if (!GlobalCache.isEnabled) {
            return lightspeed$resolveUncached(paths);
        }

        String key = Arrays.toString(paths);
        Path path = lightspeed$getResolvedPath(paths);
        if (path == null) {
            path = lightspeed$resolveUncached(paths);
            if (PathResourceCache.isUsablePath(path)) {
                lightspeed$resolvedPaths().put(key, path);
            }
        }
        return path;
    }

    @Unique
    private Path lightspeed$resolveUncached(String... paths) {
        if (lightspeed$modFile != null) {
            return lightspeed$modFile.findResource(paths);
        }
        return FileUtil.resolvePath(this.root, List.of(paths));
    }

    @Unique
    public Boolean lightspeed$exists(String resourceName) {
        lightspeed$requestExistenceCacheLoad();
        return lightspeed$getExistenceByResource().get(resourceName);
    }

    @Unique
    public void lightspeed$cacheExists(String resourceName, boolean exists) {
        lightspeed$getExistenceByResource().put(resourceName, exists);
    }

    @Unique
    public List<String> lightspeed$getFilePaths(PackType packType, String resourceNamespace, Path root) {
        return lightspeed$cache().discover(packType, resourceNamespace, root, false);
    }

    @Unique
    public List<String> lightspeed$getCachedFilePaths(PackType packType, String resourceNamespace) {
        return lightspeed$cache().cached(packType, resourceNamespace);
    }

    @Unique
    private CompletableFuture<List<String>> lightspeed$scheduleFilePathScan(PackType packType, String resourceNamespace, Path root) {
        if (!GlobalCache.isEnabled || lightspeed$id == null && !PackResourcesCacheSupport.isUnionFileSystem(this.root)) {
            return null;
        }
        String key = packType.name() + "|" + resourceNamespace;
        ResourceDirectoryScan directoryScan = lightspeed$scans().computeIfAbsent(key,
                ignored -> new ResourceDirectoryScan(root));
        return lightspeed$scanFutures().computeIfAbsent(key, ignored ->
                GlobalCache.supplyCacheAfter(lightspeed$persistedLoad(),
                        "scan resource paths " + lightspeed$id + " " + packType + " " + resourceNamespace,
                        () -> {
                            List<String> cached = lightspeed$getCachedFilePaths(packType, resourceNamespace);
                            return cached != null ? cached : lightspeed$cache().publish(packType, resourceNamespace,
                                    directoryScan.scan(""), false);
                        }));
    }

    @Unique
    private Set<String> lightspeed$getResourcePathSet(PackType packType, String namespace, List<String> paths) {
        return lightspeed$cache().pathSet(packType, namespace, paths);
    }

    @Unique
    private Map<PackType, Map<String, Set<String>>> lightspeed$resourcePathSets() {
        return lightspeed$cache().pathSets();
    }

    @Unique
    private Map<String, CompletableFuture<List<String>>> lightspeed$scanFutures() {
        return lightspeed$cache().scans();
    }

    @Unique
    private void lightspeed$requestExistenceCacheLoad() {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence || lightspeed$id == null || lightspeed$existenceCacheLoadRequested) {
            return;
        }
        lightspeed$existenceCacheLoadRequested = true;
        GlobalCache.loadPersistedCacheAsync(HAS_RESOURCE_CACHE_DIR, lightspeed$id, lightspeed$getExistenceByResource());
    }

    @Unique
    private Map<String, List<String>> lightspeed$getRelativeFilePathsMap(PackType packType) {
        return lightspeed$relativeFilePaths().computeIfAbsent(packType, ignored -> Maps.newConcurrentMap());
    }

    @Unique
    public void lightspeed$cacheNamespaces(PackType packType, Set<String> namespaces) {
        lightspeed$namespaces().put(packType, namespaces);
    }

    @Unique
    public Set<String> lightspeed$getCachedNamespaces(PackType packType) {
        return lightspeed$namespaces().get(packType);
    }

    @Unique
    private Map<String, Path> lightspeed$resolvedPaths() {
        return lightspeed$cache().resolvedPaths();
    }

    @Unique
    private Map<PackType, Set<String>> lightspeed$namespaces() {
        return lightspeed$cache().namespaces();
    }

    @Unique
    private Map<PackType, Map<String, List<String>>> lightspeed$relativeFilePaths() {
        return lightspeed$cache().lists();
    }

    @Unique
    private List<String> lightspeed$scanRelativeFilePaths(Path root) {
        return PathResourceCache.scanFiles(root);
    }

    @Unique
    private Set<String> lightspeed$scanNamespaces(PackType type) {
        return PathResourceCache.scanNamespaces(type, this::lightspeed$resolve);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lightspeed$closeEarlyModIndex(CallbackInfo ci) {
        ModPackIndex index = lightspeed$earlyModIndex;
        if (index != null) {
            index.closeArchive();
        }
    }

    @Unique
    private InputStream lightspeed$openRootFromModIndex(ModPackIndex index, String[] paths) throws java.io.IOException {
        String entryName = String.join("/", paths);
        InputStream direct = index.openEntryStream(entryName);
        if (direct != null) {
            return direct;
        }
        return Files.newInputStream(lightspeed$resolveUncached(paths));
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openResourceFromModIndex(
            ModPackIndex index,
            PackType type,
            ResourceLocation location
    ) {
        return () -> {
            String entryName = type.getDirectory() + '/' + location.getNamespace() + '/' + location.getPath();
            InputStream direct = index.openEntryStream(entryName);
            if (direct != null) {
                return direct;
            }
            Path path = lightspeed$resolveUncached(lightspeed$getPathParts(type, location));
            if (!Files.exists(path)) {
                throw new NoSuchFileException(location.toString());
            }
            return Files.newInputStream(path);
        };
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openResource(PackType type, ResourceLocation location) {
        return () -> {
            Path path = lightspeed$resolve(lightspeed$getPathParts(type, location));
            if (!Files.exists(path)) {
                throw new NoSuchFileException(location.toString());
            }
            return Files.newInputStream(path);
        };
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openRootResource(String[] paths) {
        return () -> Files.newInputStream(lightspeed$resolve(paths));
    }

    @Unique
    private static String[] lightspeed$getPathParts(PackType type, ResourceLocation location) {
        String[] resourceParts = location.getPath().split("/");
        String[] paths = new String[resourceParts.length + 2];
        paths[0] = type.getDirectory();
        paths[1] = location.getNamespace();
        System.arraycopy(resourceParts, 0, paths, 2, resourceParts.length);
        return paths;
    }

}

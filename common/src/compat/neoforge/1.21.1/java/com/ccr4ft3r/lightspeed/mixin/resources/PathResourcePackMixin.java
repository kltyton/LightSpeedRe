package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.PathResourceCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackCacheKey;
import com.ccr4ft3r.lightspeed.cache.resource.OrderedResourcePathIndex;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.google.common.collect.Maps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.ccr4ft3r.lightspeed.util.CacheUtil.HAS_RESOURCE_CACHE_DIR;

/** Caches resource discovery and summarizes invalid filenames during cached and direct enumeration. */
@Mixin(value = PathPackResources.class)
public abstract class PathResourcePackMixin implements IPathResourcePack, IPackResources {
    @Unique private final PathResourceCache lightspeed$paths = new PathResourceCache();

    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Path root;

    @Unique
    private volatile boolean lightspeed$existenceCacheLoadRequested;

    @Unique
    private IModFile lightspeed$modFile;
    @Unique
    private String lightspeed$id;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(PackLocationInfo location, Path source, CallbackInfo ci) {
        if (GlobalCache.isEnabled) {
            lightspeed$resolvedPaths();
            lightspeed$namespaces();
            GlobalCache.add(this);
        }
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
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
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return;
        }

        boolean[] fallbackToVanilla = {false};
        FileUtil.decomposePath(path).ifSuccess(parts -> {
            List<ResourceLocation> locations = new java.util.ArrayList<>();
            int[] invalidPathCount = {0};
            String[] firstInvalidPath = {null};
            try {
                Path namespaceRoot = lightspeed$resolve(type.getDirectory(), namespace).toAbsolutePath();
                List<String> cachedPaths = lightspeed$getCachedFilePaths(type, namespace);
                if (cachedPaths == null) {
                    CompletableFuture<List<String>> scan = lightspeed$scheduleFilePathScan(type, namespace, namespaceRoot);
                    cachedPaths = scan == null ? null : scan.join();
                    if (cachedPaths == null) {
                        fallbackToVanilla[0] = true;
                        return;
                    }
                }

                List<String> paths = cachedPaths;
                OrderedResourcePathIndex index = lightspeed$paths.orderedIndexes()
                        .computeIfAbsent(type, ignored -> Maps.newConcurrentMap())
                        .computeIfAbsent(namespace, ignored -> OrderedResourcePathIndex.from(paths));
                index.forEachAtOrUnder(String.join("/", parts), resourcePath -> {
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
                fallbackToVanilla[0] = true;
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
        }).ifError(dataResult -> LOGGER.error("Invalid path {}: {}", path, dataResult.message()));

        if (!fallbackToVanilla[0]) {
            ci.cancel();
        }
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
        lightspeed$paths.orderedIndexes().clear();
        lightspeed$scanFutures().clear();
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
        this.lightspeed$modFile = modFile;
        this.lightspeed$id = ResourcePackCacheKey.from(modFile, GlobalCache.shouldVerifyJarHash);
        if (lightspeed$id == null) {
            lightspeed$setExistenceByResource(Maps.newConcurrentMap());
            lightspeed$paths.bindNamespaces(Maps.newConcurrentMap());
            lightspeed$paths.bindLists(Maps.newConcurrentMap());
            return;
        }
        lightspeed$setExistenceByResource(GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$id, ignored -> Maps.newConcurrentMap()));
        lightspeed$paths.bindNamespaces(GlobalCache.PERSISTED_NAMESPACES_BY_MOD.computeIfAbsent(
                lightspeed$id, ignored -> Maps.newConcurrentMap()));
        lightspeed$paths.bindLists(GlobalCache.PERSISTED_RESOURCE_LISTS_BY_MOD.computeIfAbsent(
                lightspeed$id, ignored -> Maps.newConcurrentMap()));
    }

    @Override
    public Boolean lightspeed$hasIndexedResource(PackType type, ResourceLocation location) {
        List<String> cachedPaths = lightspeed$getCachedFilePaths(type, location.getNamespace());
        if (cachedPaths == null) {
            return null;
        }
        Set<String> indexedPaths = lightspeed$getResourcePathSet(type, location.getNamespace(), cachedPaths);
        return indexedPaths.contains(location.getPath());
    }

    @Unique
    public Path lightspeed$getResolvedPath(String... paths) {
        return lightspeed$paths.resolved(paths);
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
        return lightspeed$paths.discover(packType, resourceNamespace, root, false);
    }

    @Unique
    public List<String> lightspeed$getCachedFilePaths(PackType packType, String resourceNamespace) {
        return lightspeed$paths.cached(packType, resourceNamespace);
    }

    @Unique
    private CompletableFuture<List<String>> lightspeed$scheduleFilePathScan(PackType packType, String resourceNamespace, Path root) {
        if (!GlobalCache.isEnabled || lightspeed$id == null) {
            return null;
        }
        String key = packType.name() + "|" + resourceNamespace;
        return lightspeed$scanFutures().computeIfAbsent(key, ignored ->
                GlobalCache.supplyCacheAfterPersistedLoad(
                        "scan resource paths " + lightspeed$id + " " + packType + " " + resourceNamespace,
                        () -> {
                            List<String> cached = lightspeed$getCachedFilePaths(packType, resourceNamespace);
                            return cached != null ? cached : lightspeed$getFilePaths(packType, resourceNamespace, root);
                        }));
    }

    @Unique
    private Set<String> lightspeed$getResourcePathSet(PackType packType, String namespace, List<String> paths) {
        return lightspeed$paths.pathSet(packType, namespace, paths);
    }

    @Unique
    private Map<PackType, Map<String, Set<String>>> lightspeed$resourcePathSets() {
        return lightspeed$paths.pathSets();
    }

    @Unique
    private Map<String, CompletableFuture<List<String>>> lightspeed$scanFutures() {
        return lightspeed$paths.scans();
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
        return lightspeed$paths.resolvedPaths();
    }

    @Unique
    private Map<PackType, Set<String>> lightspeed$namespaces() {
        return lightspeed$paths.namespaces();
    }

    @Unique
    private Map<PackType, Map<String, List<String>>> lightspeed$relativeFilePaths() {
        return lightspeed$paths.lists();
    }

    @Unique
    private List<String> lightspeed$scanRelativeFilePaths(Path root) {
        return PathResourceCache.scanFiles(root);
    }

    @Unique
    private Set<String> lightspeed$scanNamespaces(PackType type) {
        return PathResourceCache.scanNamespaces(type, this::lightspeed$resolve);
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

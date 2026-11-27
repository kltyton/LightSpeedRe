package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.resource.PathResourceCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceDirectoryScan;
import com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathIndex;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePathValidationCache;
import com.ccr4ft3r.lightspeed.cache.resource.ResourcePackCacheKey;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.startup.compat.EarlyModPackIndexEngine.ModPackIndex;
import com.ccr4ft3r.lightspeed.startup.compat.PackResourcesCacheSupport;
import com.google.common.collect.Maps;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles.*;

@Mixin(value = PathPackResources.class)
public abstract class PathResourcePackMixin implements IPathResourcePack, IPackResources {
    @Unique private final PathResourceCache lightspeed$paths = new PathResourceCache();
    @Unique private final Map<String, ResourceDirectoryScan> lightspeed$directoryScans = new ConcurrentHashMap<>();

    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract Path resolve(String... paths);

    
    @Unique
    private volatile boolean lightspeed$existenceCacheLoadRequested;
    @Unique
    private volatile CompletableFuture<Void> lightspeed$persistedCacheLoad = CompletableFuture.completedFuture(null);
    @Unique
    private volatile int lightspeed$bootstrapIndexHandle = BootstrapAgentBridge.UNKNOWN;
    @Unique
    private volatile ModPackIndex lightspeed$earlyModIndex;

    @Unique
    private IModFile lightspeed$modFile;
    
    @Unique
    private String lightspeed$id;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String packId, boolean isBuiltin, Path source, CallbackInfo ci) {
        if (lightspeed$earlyModIndex == null) {
            lightspeed$earlyModIndex = PackResourcesCacheSupport.tryGetModPackIndex(source);
        }
        if (lightspeed$earlyModIndex == null) {
            lightspeed$bootstrapIndexHandle = BootstrapAgentBridge.bindResourceIndex(source);
        }
        if (GlobalCache.isEnabled) {
            lightspeed$resolvedPaths();
            lightspeed$namespaces();
            GlobalCache.add(this);
        }
    }

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true, remap = false)
    public void resolveHeadInjected(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (lightspeed$modFile == null) {
            if (!GlobalCache.isEnabled)
                return;
            Path resolved = lightspeed$getResolvedPath(paths);
            if (resolved != null)
                cir.setReturnValue(resolved);
            return;
        }
        if (!GlobalCache.isEnabled) {
            cir.setReturnValue(lightspeed$modFile.findResource(paths));
            return;
        }
        Path path = lightspeed$getResolvedPath(paths);
        if (path == null) {
            path = lightspeed$modFile.findResource(paths);
            if (PathResourceCache.isUsablePath(path)) {
                lightspeed$resolvedPaths().put(Arrays.toString(paths), path);
            }
        }

        cir.setReturnValue(path);
    }

    @Inject(method = "resolve", at = @At("RETURN"), remap = false)
    public void resolveReturnInjected(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (!GlobalCache.isEnabled || lightspeed$modFile != null)
            return;
        Path path = cir.getReturnValue();
        if (PathResourceCache.isUsablePath(path)) {
            lightspeed$resolvedPaths().put(Arrays.toString(paths), path);
        }
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            Set<String> earlyNamespaces = earlyIndex.namespaces(type);
            if (earlyNamespaces != null) {
                cir.setReturnValue(earlyNamespaces);
                return;
            }
        }
        if (lightspeed$bootstrapIndexHandle != BootstrapAgentBridge.UNKNOWN) {
            int rootExists = BootstrapAgentBridge.containsResource(lightspeed$bootstrapIndexHandle,
                    type.getDirectory());
            if (rootExists != BootstrapAgentBridge.UNKNOWN) {
                PackType namespaceType = type == PackType.SERVER_DATA && rootExists == 0
                        ? PackType.CLIENT_RESOURCES : type;
                Set<String> namespaces = BootstrapAgentBridge.resourceNamespaces(
                        lightspeed$bootstrapIndexHandle, namespaceType.getDirectory());
                if (namespaces != null) {
                    cir.setReturnValue(namespaces);
                    return;
                }
            }
        }
        if (!lightspeed$persistedCacheLoad.isDone()) {
            return;
        }
        Set<String> namespaces = lightspeed$getCachedNamespaces(type);
        if (namespaces != null)
            cir.setReturnValue(namespaces);
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"))
    public void getNamespacesReturnInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;
        if (GlobalCache.shouldCacheEmptyNamespaces || cir.getReturnValue() != null && !cir.getReturnValue().isEmpty())
            lightspeed$cacheNamespaces(type, cir.getReturnValue());
    }

    @Inject(method = "getRootResource", at = @At("HEAD"), cancellable = true)
    public void getRootResourceHeadInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
            return;
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null && !FusionPackCompat.hasOverrides(this)) {
            int state = earlyIndex.checkRoot(paths);
            if (state == 1) {
                cir.setReturnValue(lightspeed$openRootResource(Arrays.copyOf(paths, paths.length)));
                return;
            }
            if (state == 0) {
                cir.setReturnValue(null);
                return;
            }
        }
        if (!GlobalCache.shouldCacheResourceExistence)
            return;

        String cacheKey = PathResourceCache.formatPartsKey(paths);
        Boolean exists = lightspeed$exists(cacheKey);
        if (exists != null) {
            if (exists) {
                cir.setReturnValue(lightspeed$openRootResource(Arrays.copyOf(paths, paths.length)));
            } else {
                cir.setReturnValue(null);
            }
        }
    }

    @Inject(method = "getRootResource", at = @At("RETURN"))
    public void getRootResourceReturnInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null && earlyIndex.checkRoot(paths) != -1 && !FusionPackCompat.hasOverrides(this))
            return;

        String cacheKey = PathResourceCache.formatPartsKey(paths);
        boolean exists = cir.getReturnValue() != null;
        lightspeed$cacheExists(cacheKey, exists);
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

        Optional<String> pathError = lightspeed$pathValidationCache().error(path);
        if (pathError.isPresent()) {
            LOGGER.error("Invalid path {}: {}", path, pathError.orElseThrow());
            ci.cancel();
            return;
        }

        List<String> bootstrapPaths = null;
        ResourcePathIndex index = null;
        try {
            ModPackIndex earlyIndex = lightspeed$earlyModIndex;
            if (earlyIndex != null) {
                ResourcePathIndex earlyPaths = earlyIndex.pathIndex(type, namespace);
                if (earlyPaths != null) {
                    earlyPaths.forEachAtOrUnder(path, resourcePath -> {
                        ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
                        if (location == null) {
                            Util.logAndPauseIfInIde(String.format(Locale.ROOT,
                                    "Invalid path in pack: %s:%s, ignoring", namespace, resourcePath));
                        } else {
                            resourceOutput.accept(location, lightspeed$openResource(type, location));
                        }
                    });
                }
                ci.cancel();
                return;
            }
            if (lightspeed$bootstrapIndexHandle != BootstrapAgentBridge.UNKNOWN) {
                String basePrefix = type.getDirectory() + '/' + namespace;
                bootstrapPaths = BootstrapAgentBridge.resourceEntries(
                        lightspeed$bootstrapIndexHandle, basePrefix, path);
            }
            if (bootstrapPaths == null) {
                if (!lightspeed$persistedCacheLoad.isDone()) return;
                Path root = resolve(type.getDirectory(), namespace).toAbsolutePath();
                List<String> cachedPaths = lightspeed$getCachedFilePaths(type, namespace);
                if (cachedPaths == null) {
                    CompletableFuture<List<String>> scan = lightspeed$scheduleFilePathScan(type, namespace, root);
                    if (scan == null) return;
                    cachedPaths = scan.getNow(null);
                }
                if (cachedPaths != null) {
                    index = lightspeed$getResourcePathIndex(type, namespace, cachedPaths);
                } else {
                    ResourceDirectoryScan scan = lightspeed$directoryScans.get(type.name() + "|" + namespace);
                    if (scan == null) return;
                    List<String> selected = scan.scan(path);
                    if (selected == null) return;
                    // A subtree snapshot must not populate the namespace index.
                    index = ResourcePathIndex.from(selected);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Lightspeed path index lookup failed for {}:{}; falling back to vanilla resource enumeration",
                    namespace, path, e);
            return;
        }

        java.util.function.Consumer<String> emit = resourcePath -> {
            ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
            if (location == null) {
                Util.logAndPauseIfInIde(String.format(Locale.ROOT,
                        "Invalid path in pack: %s:%s, ignoring", namespace, resourcePath));
            } else {
                resourceOutput.accept(location, lightspeed$openResource(type, location));
            }
        };
        if (bootstrapPaths != null) {
            bootstrapPaths.forEach(emit);
        } else {
            index.forEachAtOrUnder(path, emit);
        }
        ci.cancel();
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$modFile != null && lightspeed$id != null) {
            if (GlobalCache.shouldCacheResourceExistence) {
                CacheFiles.persist(lightspeed$getExistenceByResource(), CacheFiles.cacheFile(HAS_RESOURCE_CACHE_DIR, lightspeed$id));
            }
            CacheFiles.persist(lightspeed$namespaces(), CacheFiles.cacheFile(NAMESPACE_CACHE_DIR, lightspeed$id));
            CacheFiles.persist(lightspeed$relativeFilePaths(), CacheFiles.cacheFile(RESOURCE_LIST_CACHE_DIR, lightspeed$id));
        }
        lightspeed$getExistenceByResource().clear();
        lightspeed$resolvedPaths().clear();
        lightspeed$namespaces().clear();
        lightspeed$relativeFilePaths().clear();
        lightspeed$resourcePathIndexes().clear();
        lightspeed$scanFutures().clear();
        lightspeed$directoryScans.clear();
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            lightspeed$earlyModIndex = null;
            earlyIndex.closeArchive();
        }
    }

    @Inject(method = "close", at = @At("RETURN"), remap = false)
    public void closeReturnInjected(CallbackInfo ci) {
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            earlyIndex.closeArchive();
        }
    }

    @Override
    public void lightspeed$setModFile(IModFile modFile) {
        this.lightspeed$modFile = modFile;
        if (lightspeed$earlyModIndex == null) {
            lightspeed$earlyModIndex = com.ccr4ft3r.lightspeed.startup.compat.PackResourcesCacheSupport
                    .tryGetModPackIndex(modFile);
        }
        if (lightspeed$earlyModIndex == null && lightspeed$bootstrapIndexHandle == BootstrapAgentBridge.UNKNOWN) {
            lightspeed$bootstrapIndexHandle = BootstrapAgentBridge.bindResourceIndex(modFile.findResource(""));
        }
        boolean usesEarlyIndex = lightspeed$earlyModIndex != null && !FusionPackCompat.hasOverrides(this);
        this.lightspeed$id = !usesEarlyIndex && PackResourcesCacheSupport.hasSingleJarModRoot(modFile)
                ? ResourcePackCacheKey.from(modFile, GlobalCache.shouldVerifyJarHash) : null;
        lightspeed$setExistenceByResource(lightspeed$id == null
                ? new java.util.concurrent.ConcurrentHashMap<>(64)
                : GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(lightspeed$id, ignored -> new java.util.concurrent.ConcurrentHashMap<>(1024)));
        lightspeed$paths.bindNamespaces(new java.util.concurrent.ConcurrentHashMap<>(8));
        lightspeed$paths.bindLists(new java.util.concurrent.ConcurrentHashMap<>(4));
        for (PackType packType : PackType.values()) {
            lightspeed$paths.lists().put(packType, new java.util.concurrent.ConcurrentHashMap<>(64));
        }
        lightspeed$persistedCacheLoad = lightspeed$id == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(
                        GlobalCache.loadPersistedCacheAsync(NAMESPACE_CACHE_DIR, lightspeed$id, lightspeed$paths.namespaces()),
                        GlobalCache.loadPersistedCacheAsync(RESOURCE_LIST_CACHE_DIR, lightspeed$id,
                                lightspeed$paths.lists()));
    }

    @Override
    public void lightspeed$startAsyncPreload() {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldAsyncPreloadPacks) {
            return;
        }
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            for (PackType packType : PackType.values()) {
                Set<String> namespaces = earlyIndex.namespaces(packType);
                if (namespaces != null) {
                    lightspeed$cacheNamespaces(packType, namespaces);
                }
            }
            return;
        }
        if (BootstrapAgentBridge.isAvailable()) {
            return;
        }
        for (PackType packType : PackType.values()) {
            GlobalCache.supplyStartupAfter(lightspeed$persistedCacheLoad,
                    "preload namespaces " + lightspeed$id + " " + packType, () -> {
                Set<String> namespaces = lightspeed$getCachedNamespaces(packType);
                if (namespaces == null) {
                    namespaces = lightspeed$scanNamespaces(packType);
                    lightspeed$cacheNamespaces(packType, namespaces);
                }
                for (String namespace : namespaces) {
                    if (lightspeed$getCachedFilePaths(packType, namespace) == null) {
                        Path root = resolve(packType.getDirectory(), namespace).toAbsolutePath();
                        lightspeed$scheduleFilePathScan(packType, namespace, root);
                    }
                }
                return null;
            });
        }
    }

    @Override
    public ResourcePathIndex lightspeed$earlyPathIndex(PackType type, String namespace) {
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        return earlyIndex == null ? null : earlyIndex.pathIndex(type, namespace);
    }

    @Override
    public Boolean lightspeed$hasIndexedResource(PackType type, ResourceLocation location) {
        PackType effectiveType = lightspeed$effectiveType(type, location);
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            return earlyIndex.contains(effectiveType, location.getNamespace(), location.getPath());
        }
        if (lightspeed$bootstrapIndexHandle != BootstrapAgentBridge.UNKNOWN) {
            int indexed = BootstrapAgentBridge.containsResource(lightspeed$bootstrapIndexHandle,
                    effectiveType.getDirectory() + '/' + location.getNamespace() + '/' + location.getPath());
            if (indexed != BootstrapAgentBridge.UNKNOWN) return indexed == 1;
        }
        if (!lightspeed$persistedCacheLoad.isDone()) {
            return null;
        }
        List<String> cachedPaths = lightspeed$getCachedFilePaths(effectiveType, location.getNamespace());
        if (cachedPaths == null) {
            return null;
        }
        boolean present = lightspeed$getResourcePathIndex(effectiveType, location.getNamespace(), cachedPaths)
                .contains(location.getPath());
        return present ? Boolean.TRUE : lightspeed$id == null ? null : Boolean.FALSE;
    }

    @Override
    public Boolean lightspeed$containsIndexed(String type, String namespace, String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return null;
        }
        PackType packType = "assets".equals(type) ? PackType.CLIENT_RESOURCES
                : "data".equals(type) ? PackType.SERVER_DATA : null;
        if (packType == null) {
            return null;
        }
        if (path != null && path.startsWith("lang/")) {
            packType = PackType.CLIENT_RESOURCES;
        }
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            return earlyIndex.contains(packType, namespace, path);
        }
        return null;
    }

    @Override
    public Boolean lightspeed$hasAnyUnder(PackType type, String namespace, String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return null;
        }
        if (lightspeed$pathValidationCache().error(path).isPresent()) {
            return null;
        }
        ModPackIndex earlyIndex = lightspeed$earlyModIndex;
        if (earlyIndex != null) {
            ResourcePathIndex earlyPaths = earlyIndex.pathIndex(type, namespace);
            return earlyPaths != null && earlyPaths.hasAtOrUnder(path);
        }
        if (lightspeed$persistedCacheLoad.isDone()) {
            List<String> cachedPaths = lightspeed$getCachedFilePaths(type, namespace);
            if (cachedPaths != null) {
                return lightspeed$getResourcePathIndex(type, namespace, cachedPaths).hasAtOrUnder(path);
            }
        }
        return null;
    }

    @Unique
    public Path lightspeed$getResolvedPath(String... paths) {
        return lightspeed$paths.resolved(paths);
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
        return lightspeed$paths.discover(packType, resourceNamespace, root, true);
    }

    @Unique
    public List<String> lightspeed$getCachedFilePaths(PackType packType, String resourceNamespace) {
        return lightspeed$paths.cached(packType, resourceNamespace);
    }

    @Unique
    private CompletableFuture<List<String>> lightspeed$scheduleFilePathScan(PackType packType, String resourceNamespace, Path root) {
        if (!GlobalCache.isEnabled) {
            return null;
        }
        String key = packType.name() + "|" + resourceNamespace;
        ResourceDirectoryScan directoryScan = lightspeed$directoryScans.computeIfAbsent(key,
                ignored -> new ResourceDirectoryScan(root));
        return lightspeed$scanFutures().computeIfAbsent(key, ignored ->
                GlobalCache.supplyStartupAfter(lightspeed$persistedCacheLoad,
                        "scan resource paths " + lightspeed$id + " " + packType + " " + resourceNamespace,
                        () -> {
                            List<String> cached = lightspeed$getCachedFilePaths(packType, resourceNamespace);
                            return cached != null ? cached : lightspeed$paths.publish(packType, resourceNamespace,
                                    directoryScan.scan(""), true);
                        }));
    }

    @Unique
    private ResourcePathIndex lightspeed$getResourcePathIndex(PackType packType, String namespace, List<String> paths) {
        return lightspeed$paths.index(packType, namespace, paths);
    }

    @Unique
    private Map<PackType, Map<String, ResourcePathIndex>> lightspeed$resourcePathIndexes() {
        return lightspeed$paths.sortedIndexes();
    }

    @Unique
    private ResourcePathValidationCache lightspeed$pathValidationCache() {
        return lightspeed$paths.validation();
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
        return PathResourceCache.scanNamespaces(type, this::resolve);
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openResource(PackType type, ResourceLocation location) {
        return () -> {
            PackType effectiveType = lightspeed$effectiveType(type, location);
            ModPackIndex earlyIndex = lightspeed$earlyModIndex;
            if (earlyIndex != null && GlobalCache.isEnabled) {
                InputStream direct = earlyIndex.openEntryStream(
                        effectiveType.getDirectory() + '/' + location.getNamespace() + '/' + location.getPath());
                if (direct != null) {
                    return direct;
                }
            }
            String[] parts = lightspeed$getPathParts(effectiveType, location);
            if (lightspeed$modFile == null || !BootstrapAgentBridge.isAvailable()) {
                return Files.newInputStream(resolve(parts));
            }
            String name = String.join("/", parts);
            byte[] cached = BootstrapAgentBridge.resourceBytes(lightspeed$bootstrapIndexHandle, name);
            if (cached != null) {
                return new ByteArrayInputStream(cached);
            }
            Path path = resolve(parts);
            byte[] bytes = Files.readAllBytes(path);
            BootstrapAgentBridge.recordResourceBytes(lightspeed$bootstrapIndexHandle, name, bytes);
            return new ByteArrayInputStream(bytes);
        };
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openRootResource(String[] paths) {
        return () -> {
            ModPackIndex earlyIndex = lightspeed$earlyModIndex;
            if (earlyIndex != null && GlobalCache.isEnabled) {
                InputStream direct = earlyIndex.openEntryStream(String.join("/", paths));
                if (direct != null) {
                    return direct;
                }
            }
            return Files.newInputStream(this.resolve(paths));
        };
    }

    @Unique
    private static PackType lightspeed$effectiveType(PackType type, ResourceLocation location) {
        return location.getPath().startsWith("lang/") ? PackType.CLIENT_RESOURCES : type;
    }

    @Unique
    private static String[] lightspeed$getPathParts(PackType type, ResourceLocation location) {
        return com.ccr4ft3r.lightspeed.startup.compat.PackResourcesCacheSupport.splitResourcePathWithPrefix(
                type.getDirectory(), location.getNamespace(), location.getPath());
    }

}

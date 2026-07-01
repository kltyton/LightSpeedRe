package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.FusionPackCompat;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.util.CacheUtil;
import com.google.common.collect.Maps;
import net.minecraft.FileUtil;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ccr4ft3r.lightspeed.util.CacheUtil.*;

@Mixin(value = PathPackResources.class)
public abstract class PathResourcePackMixin implements IPathResourcePack, IPackResources {

    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract Path resolve(String... paths);

    
    @Unique
    private Map<String, Path> lightspeed$resolvedPathByResource;
    
    @Unique
    private Map<PackType, Set<String>> lightspeed$namespacesByPackType;
    
    @Unique
    private Map<PackType, Map<String, List<String>>> lightspeed$relativeFilePathsByPackType;
    @Unique
    private Set<String> lightspeed$scheduledResourceListScans;
    @Unique
    private volatile boolean lightspeed$existenceCacheLoadRequested;

    
    @Unique
    private IModFile lightspeed$modFile;
    
    @Unique
    private String lightspeed$id;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String packId, boolean isBuiltin, Path source, CallbackInfo ci) {
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
            if (lightspeed$isUsablePath(path)) {
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
        if (lightspeed$isUsablePath(path)) {
            lightspeed$resolvedPaths().put(Arrays.toString(paths), path);
        }
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled || FusionPackCompat.hasOverrides(this))
            return;
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
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        String cacheKey = Arrays.toString(paths);
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

        String cacheKey = Arrays.toString(paths);
        boolean exists = cir.getReturnValue() != null;
        lightspeed$cacheExists(cacheKey, exists);
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    public void listResourcesHeadInjected(PackType type, String namespace, String path, PackResources.ResourceOutput resourceOutput, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || FusionPackCompat.hasOverrides(this)) {
            return;
        }

        boolean[] fallbackToVanilla = {false};
        FileUtil.decomposePath(path).get().ifLeft(parts -> {
            try {
                Path root = resolve(type.getDirectory(), namespace).toAbsolutePath();
                List<String> cachedPaths = lightspeed$getCachedFilePaths(type, namespace);
                if (cachedPaths == null) {
                    lightspeed$scheduleFilePathScan(type, namespace, root);
                    fallbackToVanilla[0] = true;
                    return;
                }

                Path requestedRoot = FileUtil.resolvePath(root, parts);
                for (String cachedPath : cachedPaths) {
                    Path candidate = root.resolve(cachedPath);
                    if (!candidate.startsWith(requestedRoot)) {
                        continue;
                    }

                    String resourcePath = cachedPath.replace(candidate.getFileSystem().getSeparator(), "/");
                    ResourceLocation location = ResourceLocation.tryBuild(namespace, resourcePath);
                    if (location == null) {
                        Util.logAndPauseIfInIde(String.format(Locale.ROOT, "Invalid path in pack: %s:%s, ignoring", namespace, resourcePath));
                    } else {
                        resourceOutput.accept(location, lightspeed$openResource(type, location));
                    }
                }
            } catch (RuntimeException e) {
                fallbackToVanilla[0] = true;
                LOGGER.warn("Lightspeed path index lookup failed for {}:{}; falling back to vanilla resource enumeration",
                        namespace, path, e);
            }
        }).ifRight(dataResult -> LOGGER.error("Invalid path {}: {}", path, dataResult.message()));

        if (!fallbackToVanilla[0]) {
            ci.cancel();
        }
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$modFile != null) {
            if (GlobalCache.shouldCacheResourceExistence) {
                CacheUtil.persist(lightspeed$getExistenceByResource(), new File(HAS_RESOURCE_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
            }
            CacheUtil.persist(lightspeed$namespaces(), new File(NAMESPACE_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
            CacheUtil.persist(lightspeed$relativeFilePaths(), new File(RESOURCE_LIST_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
        }
        lightspeed$getExistenceByResource().clear();
        lightspeed$resolvedPaths().clear();
        lightspeed$namespaces().clear();
        lightspeed$relativeFilePaths().clear();
    }

    @Override
    public void lightspeed$setModFile(IModFile modFile) {
        this.lightspeed$modFile = modFile;
        this.lightspeed$id = modFile.getModFileInfo().moduleName() + modFile.getModFileInfo().versionString()
                + "-" + FilenameUtils.getBaseName(modFile.getFilePath().toString()).replaceAll("[^a-zA-Z0-9.-]", "");
        lightspeed$setExistenceByResource(GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$id, i -> Maps.newConcurrentMap()));
        lightspeed$namespacesByPackType = GlobalCache.PERSISTED_NAMESPACES_BY_MOD.computeIfAbsent(
                lightspeed$id, i -> Maps.newConcurrentMap());
        lightspeed$relativeFilePathsByPackType = GlobalCache.PERSISTED_RESOURCE_LISTS_BY_MOD.computeIfAbsent(
                lightspeed$id, i -> Maps.newConcurrentMap());
    }

    @Override
    public void lightspeed$startAsyncPreload() {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldAsyncPreloadPacks) {
            return;
        }

        for (PackType packType : PackType.values()) {
            if (lightspeed$getCachedNamespaces(packType) == null) {
                GlobalCache.executeCacheLogged("preload namespaces " + lightspeed$id + " " + packType, () -> {
                    if (lightspeed$getCachedNamespaces(packType) == null) {
                        lightspeed$cacheNamespaces(packType, lightspeed$scanNamespaces(packType));
                    }
                });
            }

            Map<String, List<String>> cachedLists = lightspeed$getRelativeFilePathsMap(packType);
            for (String namespace : new ArrayList<>(cachedLists.keySet())) {
                if (cachedLists.get(namespace) == null) {
                    GlobalCache.executeCacheLogged("preload " + lightspeed$id + " " + packType + " " + namespace, () -> {
                        Path root = resolve(packType.getDirectory(), namespace).toAbsolutePath();
                        lightspeed$getFilePaths(packType, namespace, root);
                    });
                }
            }
        }
    }

    
    @Unique
    public Path lightspeed$getResolvedPath(String... paths) {
        String key = Arrays.toString(paths);
        Path path = lightspeed$resolvedPaths().get(key);
        if (path != null && !lightspeed$isUsablePath(path)) {
            lightspeed$resolvedPaths().remove(key);
            return null;
        }
        return path;
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
        Map<String, List<String>> relativePaths = lightspeed$getRelativeFilePathsMap(packType);
        List<String> cachedRelativePaths = relativePaths.get(resourceNamespace);
        if (cachedRelativePaths != null) {
            return cachedRelativePaths;
        }

        List<String> scannedPaths = lightspeed$scanRelativeFilePaths(root);
        if (scannedPaths != null) {
            relativePaths.put(resourceNamespace, scannedPaths);
        }
        return scannedPaths;
    }

    @Unique
    public List<String> lightspeed$getCachedFilePaths(PackType packType, String resourceNamespace) {
        return lightspeed$getRelativeFilePathsMap(packType).get(resourceNamespace);
    }

    @Unique
    private void lightspeed$scheduleFilePathScan(PackType packType, String resourceNamespace, Path root) {
        if (!GlobalCache.isEnabled || lightspeed$id == null) {
            return;
        }
        String key = packType.name() + "|" + resourceNamespace;
        if (!lightspeed$scheduledScans().add(key)) {
            return;
        }

        GlobalCache.executeCacheLogged("scan resource paths " + lightspeed$id + " " + packType + " " + resourceNamespace, () -> {
            if (lightspeed$getCachedFilePaths(packType, resourceNamespace) == null) {
                List<String> scannedPaths = lightspeed$scanRelativeFilePaths(root);
                if (scannedPaths != null) {
                    lightspeed$getRelativeFilePathsMap(packType).put(resourceNamespace, scannedPaths);
                }
            }
        });
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
        if (lightspeed$resolvedPathByResource == null) {
            lightspeed$resolvedPathByResource = Maps.newConcurrentMap();
        }
        return lightspeed$resolvedPathByResource;
    }

    @Unique
    private Map<PackType, Set<String>> lightspeed$namespaces() {
        if (lightspeed$namespacesByPackType == null) {
            lightspeed$namespacesByPackType = Maps.newConcurrentMap();
        }
        return lightspeed$namespacesByPackType;
    }

    @Unique
    private Map<PackType, Map<String, List<String>>> lightspeed$relativeFilePaths() {
        if (lightspeed$relativeFilePathsByPackType == null) {
            lightspeed$relativeFilePathsByPackType = Maps.newConcurrentMap();
            for (PackType packType : PackType.values()) {
                lightspeed$relativeFilePathsByPackType.put(packType, Maps.newConcurrentMap());
            }
        }
        return lightspeed$relativeFilePathsByPackType;
    }

    @Unique
    private Set<String> lightspeed$scheduledScans() {
        if (lightspeed$scheduledResourceListScans == null) {
            lightspeed$scheduledResourceListScans = Collections.newSetFromMap(Maps.newConcurrentMap());
        }
        return lightspeed$scheduledResourceListScans;
    }

    @Unique
    private List<String> lightspeed$scanRelativeFilePaths(Path root) {
        try (Stream<Path> stream = Files.find(root, Integer.MAX_VALUE, (candidate, attributes) -> attributes.isRegularFile())) {
            return lightspeed$toRelativePaths(root, stream.collect(Collectors.toList()));
        } catch (NoSuchFileException ignored) {
            return Collections.emptyList();
        } catch (IOException e) {
            LOGGER.error("Failed to list path {}", root, e);
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to list path {}; falling back to vanilla resource enumeration", root, e);
            return null;
        }
    }

    @Unique
    private static List<String> lightspeed$toRelativePaths(Path root, List<Path> paths) {
        String separator = root.getFileSystem().getSeparator();
        return paths.stream()
                .map(root::relativize)
                .map(Path::toString)
                .map(path -> path.replace(separator, "/"))
                .collect(Collectors.toList());
    }

    @Unique
    private Set<String> lightspeed$scanNamespaces(PackType type) {
        try {
            Path root = resolve(type.getDirectory());
            try (Stream<Path> walker = Files.walk(root, 1)) {
                return walker
                        .filter(Files::isDirectory)
                        .map(root::relativize)
                        .filter(path -> path.getNameCount() > 0)
                        .map(path -> path.toString().replaceAll("/$", ""))
                        .filter(namespace -> !namespace.isEmpty())
                        .collect(Collectors.toSet());
            }
        } catch (IOException e) {
            if (type == PackType.SERVER_DATA) {
                return lightspeed$scanNamespaces(PackType.CLIENT_RESOURCES);
            }
            return Collections.emptySet();
        }
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openResource(PackType type, ResourceLocation location) {
        return () -> {
            IoSupplier<InputStream> supplier = ((PathPackResources) (Object) this).getResource(type, location);
            if (supplier == null) {
                throw new NoSuchFileException(location.toString());
            }
            return supplier.get();
        };
    }

    @Unique
    private IoSupplier<InputStream> lightspeed$openRootResource(String[] paths) {
        return () -> Files.newInputStream(this.resolve(paths));
    }

    @Unique
    private static boolean lightspeed$isUsablePath(Path path) {
        try {
            return path != null && path.getFileSystem().isOpen();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

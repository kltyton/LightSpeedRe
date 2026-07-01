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
    private Map<PackType, Map<String, List<Path>>> lightspeed$filePathsByRootByPackType;

    
    @Unique
    private IModFile lightspeed$modFile;
    
    @Unique
    private String lightspeed$id;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String packId, boolean isBuiltin, Path source, CallbackInfo ci) {
        if (GlobalCache.isEnabled) {
            lightspeed$resolvedPaths();
            lightspeed$namespaces();
            lightspeed$filePathsByRoot();
            GlobalCache.add(this);
        }
    }

    
    @Unique
    private static Map<PackType, Map<String, List<Path>>> lightspeed$initPathsMap() {
        Map<PackType, Map<String, List<Path>>> map = Maps.newConcurrentMap();
        for (PackType packType : PackType.values()) {
            map.put(packType, Maps.newConcurrentMap());
        }
        return map;
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
        if (path == null)
            lightspeed$resolvedPaths().put(Arrays.toString(paths), path = lightspeed$modFile.findResource(paths));

        cir.setReturnValue(path);
    }

    @Inject(method = "resolve", at = @At("RETURN"), remap = false)
    public void resolveReturnInjected(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (!GlobalCache.isEnabled || lightspeed$modFile != null)
            return;
        lightspeed$resolvedPaths().put(Arrays.toString(paths), cir.getReturnValue());
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
        if (!GlobalCache.isEnabled)
            return;

        String cacheKey = Arrays.toString(paths);
        Boolean exists = lightspeed$exists(cacheKey);

        if (exists != null) {
            if (exists) {
                Path path = this.resolve(paths);
                cir.setReturnValue(IoSupplier.create(path));
            } else {
                cir.setReturnValue(null);
            }
        }
    }

    @Inject(method = "getRootResource", at = @At("RETURN"))
    public void getRootResourceReturnInjected(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
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

        FileUtil.decomposePath(path).get().ifLeft(parts -> {
            Path root = resolve(type.getDirectory(), namespace).toAbsolutePath();
            Path requestedRoot = FileUtil.resolvePath(root, parts);
            List<Path> cachedPaths = lightspeed$getFilePaths(type, namespace, root);

            for (Path candidate : cachedPaths) {
                if (!candidate.startsWith(requestedRoot)) {
                    continue;
                }

                String relativePath = root.relativize(candidate).toString().replace(candidate.getFileSystem().getSeparator(), "/");
                ResourceLocation location = ResourceLocation.tryBuild(namespace, relativePath);
                if (location == null) {
                    Util.logAndPauseIfInIde(String.format(Locale.ROOT, "Invalid path in pack: %s:%s, ignoring", namespace, relativePath));
                } else {
                    resourceOutput.accept(location, IoSupplier.create(candidate));
                }
            }
        }).ifRight(dataResult -> LOGGER.error("Invalid path {}: {}", path, dataResult.message()));

        ci.cancel();
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$modFile != null) {
            CacheUtil.persist(lightspeed$getExistenceByResource(), new File(HAS_RESOURCE_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
            CacheUtil.persist(lightspeed$namespaces(), new File(NAMESPACE_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
        }
        lightspeed$getExistenceByResource().clear();
        lightspeed$resolvedPaths().clear();
        lightspeed$namespaces().clear();
        lightspeed$filePathsByRoot().clear();
    }

    @Override
    public void lightspeed$setModFile(IModFile modFile) {
        GlobalCache.awaitPersistedCachesLoaded();
        this.lightspeed$modFile = modFile;
        this.lightspeed$id = modFile.getModFileInfo().moduleName() + modFile.getModFileInfo().versionString()
                + "-" + FilenameUtils.getBaseName(modFile.getFilePath().toString()).replaceAll("[^a-zA-Z0-9.-]", "");
        lightspeed$setExistenceByResource(GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$id, i -> Maps.newConcurrentMap()));
        lightspeed$namespaces().putAll(GlobalCache.PERSISTED_NAMESPACES_BY_MOD.computeIfAbsent(
                lightspeed$id, i -> Maps.newConcurrentMap()));
    }

    @Override
    public void lightspeed$startAsyncPreload() {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldAsyncPreloadPacks) {
            return;
        }

        for (PackType packType : PackType.values()) {
            GlobalCache.executeLogged("preload " + lightspeed$id + " " + packType, () -> {
                Set<String> namespaces = lightspeed$getCachedNamespaces(packType);
                if (namespaces == null) {
                    namespaces = lightspeed$scanNamespaces(packType);
                    lightspeed$cacheNamespaces(packType, namespaces);
                }

                for (String namespace : namespaces) {
                    Path root = resolve(packType.getDirectory(), namespace).toAbsolutePath();
                    lightspeed$getFilePaths(packType, namespace, root);
                }
            });
        }
    }

    
    @Unique
    public Path lightspeed$getResolvedPath(String... paths) {
        return lightspeed$resolvedPaths().get(Arrays.toString(paths));
    }

    
    @Unique
    public Boolean lightspeed$exists(String resourceName) {
        return lightspeed$getExistenceByResource().get(resourceName);
    }

    
    @Unique
    public void lightspeed$cacheExists(String resourceName, boolean exists) {
        lightspeed$getExistenceByResource().put(resourceName, exists);
    }

    @Unique
    public List<Path> lightspeed$getFilePaths(PackType packType, String resourceNamespace, Path root) {
        return lightspeed$getFilePathsMap(packType).computeIfAbsent(resourceNamespace, ignored -> lightspeed$scanFilePaths(root));
    }

    @Unique
    private Map<String, List<Path>> lightspeed$getFilePathsMap(PackType packType) {
        return lightspeed$filePathsByRoot().computeIfAbsent(packType, ignored -> Maps.newConcurrentMap());
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
    private Map<PackType, Map<String, List<Path>>> lightspeed$filePathsByRoot() {
        if (lightspeed$filePathsByRootByPackType == null) {
            lightspeed$filePathsByRootByPackType = lightspeed$initPathsMap();
        }
        return lightspeed$filePathsByRootByPackType;
    }

    @Unique
    private List<Path> lightspeed$scanFilePaths(Path root) {
        try (Stream<Path> stream = Files.find(root, Integer.MAX_VALUE, (candidate, attributes) -> attributes.isRegularFile())) {
            return stream.collect(Collectors.toList());
        } catch (NoSuchFileException ignored) {
            return Collections.emptyList();
        } catch (IOException e) {
            LOGGER.error("Failed to list path {}", root, e);
            return Collections.emptyList();
        }
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
}

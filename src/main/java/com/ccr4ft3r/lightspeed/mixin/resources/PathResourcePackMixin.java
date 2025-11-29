package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import com.ccr4ft3r.lightspeed.util.CacheUtil;
import com.google.common.collect.Maps;
import net.minecraft.FileUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources; // 注意: ResourceOutput 在这里
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;
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
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ccr4ft3r.lightspeed.util.CacheUtil.*;

@Mixin(value = PathPackResources.class)
public abstract class PathResourcePackMixin implements IPathResourcePack, IPackResources {

    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract Path resolve(String... paths);

    
    private final Map<String, Path> lightspeed$resolvedPathByResource = Maps.newConcurrentMap();
    
    private final Map<PackType, Set<String>> lightspeed$namespacesByPackType = Maps.newConcurrentMap();
    
    private final Map<PackType, Map<String, List<Path>>> lightspeed$filePathsByRootByPackType = lightspeed$initPathsMap();

    
    @Unique
    private IModFile lightspeed$modFile;
    
    @Unique
    private String lightspeed$id;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String packId, boolean isBuiltin, Path source, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
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
            lightspeed$resolvedPathByResource.put(Arrays.toString(paths), path = lightspeed$modFile.findResource(paths));

        cir.setReturnValue(path);
    }

    @Inject(method = "resolve", at = @At("RETURN"), remap = false)
    public void resolveReturnInjected(String[] paths, CallbackInfoReturnable<Path> cir) {
        if (!GlobalCache.isEnabled || lightspeed$modFile != null)
            return;
        lightspeed$resolvedPathByResource.put(Arrays.toString(paths), cir.getReturnValue());
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    public void getNamespacesHeadInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled)
            return;
        Set<String> namespaces = lightspeed$getCachedNamespaces(type);
        if (namespaces != null)
            cir.setReturnValue(namespaces);
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"))
    public void getNamespacesReturnInjected(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (!GlobalCache.isEnabled)
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

    /**
     * @author ccR4ft3r, kltyton
     * @reason Implement caching for file walking [replacing 1.19.2 getResources(old)] (replacing 1.20.1 listResources)
     */
    @Overwrite
    public void listResources(@NotNull PackType type, @NotNull String namespace, @NotNull String path, PackResources.@NotNull ResourceOutput resourceOutput) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths) {
            FileUtil.decomposePath(path).get()
                    .ifLeft(parts -> net.minecraft.server.packs.PathPackResources.listPath(namespace, resolve(type.getDirectory(), namespace).toAbsolutePath(), parts, resourceOutput))
                    .ifRight(dataResult -> LOGGER.error("Invalid path {}: {}", path, dataResult.message()));
            return;
        }

        List<Path> paths = lightspeed$getFilePaths(type, namespace);

        if (paths == null) {
            try {
                Path root = resolve(type.getDirectory(), namespace).toAbsolutePath();
                Path inputPath = root.resolve(path.replace("/", root.getFileSystem().getSeparator())); // 简单处理 path

                if (Files.exists(inputPath)) {
                    try (Stream<Path> pathStream = Files.walk(inputPath)) {
                        paths = pathStream
                                .filter(p -> !p.toString().endsWith(".mcmeta")) // 过滤 mcmeta
                                .filter(p -> !Files.isDirectory(p)) // 过滤文件夹
                                .collect(Collectors.toList());
                    }
                } else {
                    paths = Collections.emptyList();
                }
            } catch (IOException e) {
                paths = Collections.emptyList();
            }
            lightspeed$cacheFilePaths(type, namespace, paths == null ? Collections.emptyList() : paths);
        }

        if (paths != null && !paths.isEmpty()) {
            Path root = resolve(type.getDirectory(), namespace).toAbsolutePath();
            paths.parallelStream().forEach(p -> {
                try {
                    String relativePath = root.relativize(p).toString().replace(File.separator, "/");
                    ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, relativePath);
                    resourceOutput.accept(location, IoSupplier.create(p));
                } catch (Exception e) {
                }
            });
        }
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$modFile != null) {
            CacheUtil.persist(lightspeed$getExistenceByResource(), new File(HAS_RESOURCE_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
            CacheUtil.persist(lightspeed$namespacesByPackType, new File(NAMESPACE_CACHE_DIR.getPath(), lightspeed$id + ".ser"));
        }
        lightspeed$getExistenceByResource().clear();
        lightspeed$resolvedPathByResource.clear();
        lightspeed$namespacesByPackType.clear();
        lightspeed$filePathsByRootByPackType.clear();
    }

    @Override
    public void lightspeed$setModFile(IModFile modFile) {
        this.lightspeed$modFile = modFile;
        this.lightspeed$id = modFile.getModFileInfo().moduleName() + modFile.getModFileInfo().versionString()
                + "-" + FilenameUtils.getBaseName(modFile.getFilePath().toString()).replaceAll("[^a-zA-Z0-9.-]", "");
        lightspeed$setExistenceByResource(GlobalCache.PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$id, i -> Maps.newConcurrentMap()));
    }

    
    @Unique
    public Path lightspeed$getResolvedPath(String... paths) {
        return lightspeed$resolvedPathByResource.get(Arrays.toString(paths));
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
    public void lightspeed$cacheFilePaths(PackType packType, String resourceNamespace, List<Path> filePaths) {
        lightspeed$getFilePathsMap(packType).putIfAbsent(resourceNamespace, filePaths);
    }

    
    @Unique
    public List<Path> lightspeed$getFilePaths(PackType packType, String resourceNamespace) {
        return lightspeed$getFilePathsMap(packType).get(resourceNamespace);
    }

    
    @Unique
    private Map<String, List<Path>> lightspeed$getFilePathsMap(PackType packType) {
        return lightspeed$filePathsByRootByPackType.get(packType);
    }

    
    @Unique
    public void lightspeed$cacheNamespaces(PackType packType, Set<String> namespaces) {
        lightspeed$namespacesByPackType.put(packType, namespaces);
    }

    
    @Unique
    public Set<String> lightspeed$getCachedNamespaces(PackType packType) {
        return lightspeed$namespacesByPackType.get(packType);
    }
}
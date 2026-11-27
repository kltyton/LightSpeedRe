package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles;
import com.ccr4ft3r.lightspeed.cache.resource.ResourceOutputEmitter;
import com.ccr4ft3r.lightspeed.cache.resource.VanillaPackIndex;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.interfaces.IndexedPackResources;
import com.ccr4ft3r.lightspeed.platform.LoaderEnvironment;
import com.ccr4ft3r.lightspeed.platform.StartupCachePlatform;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.ccr4ft3r.lightspeed.cache.GlobalCache.PERSISTED_EXISTENCES_BY_MOD;
import static com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles.HAS_RESOURCE_CACHE_DIR;

@Mixin(VanillaPackResources.class)
public abstract class VanillaPackResourcesMixin implements IPackResources, IndexedPackResources {
    @Unique
    private static final Object LIGHTSPEED$ABSENT = new Object();

    @Unique
    private static final Object LIGHTSPEED$PRESENT_UNCACHED = new Object();

    @Shadow
    @Final
    private Map<PackType, List<Path>> pathsForType;

    @Unique
    private volatile Map<String, Boolean> lightspeed$existencePerClientResource;

    @Unique
    private volatile Map<String, Boolean> lightspeed$existencePerServerResource;

    /** Startup-only ResourceLocation/Identifier -> IoSupplier or sentinel cache avoiding toString() on repeat hits. */
    @Unique
    private volatile Map<Object, Object> lightspeed$clientFastLookup;

    @Unique
    private volatile Map<Object, Object> lightspeed$serverFastLookup;

    @Unique
    private volatile VanillaPackIndex lightspeed$packIndex;

    @Unique
    private String lightspeed$versionId;

    @Unique
    private boolean lightspeed$isNativeVanilla() {
        return ((Object) this).getClass() == VanillaPackResources.class;
    }

    @Unique
    private VanillaPackIndex lightspeed$index() {
        VanillaPackIndex index = lightspeed$packIndex;
        if (index == null) {
            synchronized (this) {
                index = lightspeed$packIndex;
                if (index == null) {
                    index = new VanillaPackIndex();
                    lightspeed$packIndex = index;
                }
            }
        }
        return index;
    }

    @Unique
    private Map<String, Boolean> lightspeed$existenceMap(boolean isClient) {
        Map<String, Boolean> map = isClient ? lightspeed$existencePerClientResource : lightspeed$existencePerServerResource;
        if (map == null) {
            synchronized (this) {
                map = isClient ? lightspeed$existencePerClientResource : lightspeed$existencePerServerResource;
                if (map == null) {
                    map = new ConcurrentHashMap<>(4096);
                    if (isClient) {
                        lightspeed$existencePerClientResource = map;
                    } else {
                        lightspeed$existencePerServerResource = map;
                    }
                }
            }
        }
        return map;
    }

    @Unique
    private Map<Object, Object> lightspeed$fastMap(boolean isClient) {
        Map<Object, Object> map = isClient ? lightspeed$clientFastLookup : lightspeed$serverFastLookup;
        if (map == null) {
            synchronized (this) {
                map = isClient ? lightspeed$clientFastLookup : lightspeed$serverFastLookup;
                if (map == null) {
                    map = new ConcurrentHashMap<>(2048);
                    if (isClient) {
                        lightspeed$clientFastLookup = map;
                    } else {
                        lightspeed$serverFastLookup = map;
                    }
                }
            }
        }
        return map;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(CallbackInfo ci) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        GlobalCache.add(this);
        lightspeed$versionId = LoaderEnvironment.gameVersion();
        lightspeed$existencePerClientResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$versionId + "-client", k -> new ConcurrentHashMap<>(4096));
        lightspeed$existencePerServerResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$versionId + "-server", k -> new ConcurrentHashMap<>(4096));
        GlobalCache.loadPersistedCacheAsync(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-client", lightspeed$existencePerClientResource);
        GlobalCache.loadPersistedCacheAsync(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-server", lightspeed$existencePerServerResource);
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType packType, @Coerce Object location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || location == null) {
            return;
        }
        if (GlobalCache.shouldCacheWalkedPaths && lightspeed$isNativeVanilla()) {
            VanillaPackIndex index = lightspeed$packIndex;
            VanillaPackIndex.TypeSnapshot snapshot = index == null ? null : index.getIfLoaded(packType);
            if (snapshot != null) {
                String ns = ResourceOutputEmitter.namespace(location);
                String rel = ResourceOutputEmitter.path(location);
                if (ns != null && rel != null && index.isValidPath(rel)) {
                    cir.setReturnValue(snapshot.getResource(ns, rel));
                    return;
                }
            }
        }
        if (!GlobalCache.shouldCacheResourceExistence) {
            return;
        }
        boolean isClient = packType == PackType.CLIENT_RESOURCES;
        Map<Object, Object> fastMap = lightspeed$fastMap(isClient);
        Object fast = fastMap.get(location);
        if (fast != null) {
            if (fast == LIGHTSPEED$ABSENT) {
                cir.setReturnValue(null);
            } else if (fast instanceof IoSupplier<?> supplier) {
                cir.setReturnValue((IoSupplier<InputStream>) supplier);
            }
            return;
        }
        Boolean exists = lightspeed$existenceMap(isClient).get(location.toString());
        if (Boolean.FALSE.equals(exists)) {
            fastMap.put(location, LIGHTSPEED$ABSENT);
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getResource", at = @At("RETURN"))
    public void getResourceReturnInjected(PackType packType, @Coerce Object location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence || location == null) {
            return;
        }
        if (GlobalCache.shouldCacheWalkedPaths && lightspeed$isNativeVanilla()) {
            VanillaPackIndex index = lightspeed$packIndex;
            if (index != null && index.getIfLoaded(packType) != null) {
                return;
            }
        }
        boolean isClient = packType == PackType.CLIENT_RESOURCES;
        Map<Object, Object> fastMap = lightspeed$fastMap(isClient);
        if (fastMap.containsKey(location)) {
            return;
        }
        IoSupplier<InputStream> result = cir.getReturnValue();
        boolean actuallyExists = result != null;
        Object fastValue = actuallyExists
                ? (lightspeed$isNativeVanilla() ? result : LIGHTSPEED$PRESENT_UNCACHED)
                : LIGHTSPEED$ABSENT;
        fastMap.put(location, fastValue);

        Map<String, Boolean> persisted = lightspeed$existenceMap(isClient);
        String key = location.toString();
        Boolean previous = persisted.get(key);
        if (previous == null || previous.booleanValue() != actuallyExists) {
            persisted.put(key, actuallyExists);
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    private void lightspeed$listIndexed(PackType packType, String namespace, String path,
            PackResources.ResourceOutput output, CallbackInfo ci) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || !lightspeed$isNativeVanilla()) {
            return;
        }
        VanillaPackIndex index = lightspeed$index();
        if (!index.isValidPath(path)) {
            return;
        }
        VanillaPackIndex.TypeSnapshot snapshot = index.getOrBuild(packType, pathsForType);
        if (snapshot != null) {
            snapshot.listResources(namespace, path, output);
            ci.cancel();
        }
    }

    @Override
    public Boolean lightspeed$containsIndexed(String type, String namespace, String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || !lightspeed$isNativeVanilla()) {
            return null;
        }
        PackType packType = "assets".equals(type) ? PackType.CLIENT_RESOURCES
                : "data".equals(type) ? PackType.SERVER_DATA : null;
        if (packType == null) {
            return null;
        }
        VanillaPackIndex index = lightspeed$packIndex;
        if (index == null || !index.isValidPath(path)) {
            return null;
        }
        VanillaPackIndex.TypeSnapshot snapshot = index.getIfLoaded(packType);
        return snapshot == null ? null : snapshot.contains(namespace, path);
    }

    @Override
    public Boolean lightspeed$hasAnyUnder(PackType type, String namespace, String path) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheWalkedPaths || !lightspeed$isNativeVanilla()) {
            return null;
        }
        VanillaPackIndex index = lightspeed$index();
        if (!index.isValidPath(path)) {
            return null;
        }
        VanillaPackIndex.TypeSnapshot snapshot = index.getOrBuild(type, pathsForType);
        return snapshot == null ? null : snapshot.hasAnyUnder(namespace, path);
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (!StartupCachePlatform.SHARED_PERSISTENCE) {
            if (lightspeed$versionId != null) {
                Map<String, Boolean> client = lightspeed$existencePerClientResource;
                if (client != null) {
                    CacheFiles.persist(client, CacheFiles.cacheFile(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-client"));
                    client.clear();
                }
                Map<String, Boolean> server = lightspeed$existencePerServerResource;
                if (server != null) {
                    CacheFiles.persist(server, CacheFiles.cacheFile(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-server"));
                    server.clear();
                }
            }
        }
        Map<Object, Object> clientFast = lightspeed$clientFastLookup;
        if (clientFast != null) {
            clientFast.clear();
            lightspeed$clientFastLookup = null;
        }
        Map<Object, Object> serverFast = lightspeed$serverFastLookup;
        if (serverFast != null) {
            serverFast.clear();
            lightspeed$serverFastLookup = null;
        }
        VanillaPackIndex index = lightspeed$packIndex;
        if (index != null) {
            index.clear();
            lightspeed$packIndex = null;
        }
    }
}

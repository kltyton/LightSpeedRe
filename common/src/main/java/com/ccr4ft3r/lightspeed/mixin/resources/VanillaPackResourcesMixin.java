package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.google.common.collect.Maps;
import com.ccr4ft3r.lightspeed.platform.LoaderEnvironment;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.Map;

import static com.ccr4ft3r.lightspeed.cache.GlobalCache.PERSISTED_EXISTENCES_BY_MOD;
import static com.ccr4ft3r.lightspeed.cache.persistence.CacheFiles.HAS_RESOURCE_CACHE_DIR;

@Mixin(VanillaPackResources.class)
public abstract class VanillaPackResourcesMixin implements IPackResources {
    
    @Unique
    private volatile Map<String, Boolean> lightspeed$existencePerClientResource;
    
    @Unique
    private volatile Map<String, Boolean> lightspeed$existencePerServerResource;

    @Unique
    private Map<String, Boolean> lightspeed$existenceMap(boolean isClient) {
        Map<String, Boolean> map = isClient ? lightspeed$existencePerClientResource : lightspeed$existencePerServerResource;
        if (map == null) {
            synchronized (this) {
                map = isClient ? lightspeed$existencePerClientResource : lightspeed$existencePerServerResource;
                if (map == null) {
                    map = new java.util.concurrent.ConcurrentHashMap<>(4096);
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
    private String lightspeed$versionId;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(CallbackInfo ci) {
        if (!GlobalCache.isEnabled)
            return;

        GlobalCache.add(this);
        lightspeed$versionId = LoaderEnvironment.gameVersion();

        lightspeed$existencePerClientResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$versionId + "-client", k -> new java.util.concurrent.ConcurrentHashMap<>(4096));
        lightspeed$existencePerServerResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$versionId + "-server", k -> new java.util.concurrent.ConcurrentHashMap<>(4096));
        GlobalCache.loadPersistedCacheAsync(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-client", lightspeed$existencePerClientResource);
        GlobalCache.loadPersistedCacheAsync(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-server", lightspeed$existencePerServerResource);
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType packType, @Coerce Object location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        Boolean exists = lightspeed$exists(packType, location.toString());

        if (Boolean.FALSE.equals(exists)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getResource", at = @At("RETURN"))
    public void getResourceReturnInjected(PackType packType, @Coerce Object location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled || !GlobalCache.shouldCacheResourceExistence)
            return;

        boolean actuallyExists = cir.getReturnValue() != null;
        lightspeed$cacheExists(packType, location.toString(), actuallyExists);
    }

    
    @Unique
    public Boolean lightspeed$exists(PackType packType, String resourceName) {
        if (packType == PackType.CLIENT_RESOURCES)
            return lightspeed$existenceMap(true).get(resourceName);
        return lightspeed$existenceMap(false).get(resourceName);
    }

    
    @Unique
    public void lightspeed$cacheExists(PackType packType, String resourceName, boolean exists) {
        if (packType == PackType.CLIENT_RESOURCES)
            lightspeed$existenceMap(true).put(resourceName, exists);
        else
            lightspeed$existenceMap(false).put(resourceName, exists);
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$versionId != null) {
            CacheFiles.persist(lightspeed$existenceMap(true),
                    CacheFiles.cacheFile(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-client"));
            CacheFiles.persist(lightspeed$existenceMap(false),
                    CacheFiles.cacheFile(HAS_RESOURCE_CACHE_DIR, lightspeed$versionId + "-server"));
        }
        lightspeed$existenceMap(true).clear();
        lightspeed$existenceMap(false).clear();
    }
}

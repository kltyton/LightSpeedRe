package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.ccr4ft3r.lightspeed.util.CacheUtil;
import com.google.common.collect.Maps;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

import static com.ccr4ft3r.lightspeed.cache.GlobalCache.PERSISTED_EXISTENCES_BY_MOD;
import static com.ccr4ft3r.lightspeed.util.CacheUtil.HAS_RESOURCE_CACHE_DIR;

@Mixin(VanillaPackResources.class)
public abstract class VanillaPackResourcesMixin implements IPackResources {
    
    @Unique
    private Map<String, Boolean> lightspeed$existencePerClientResource = Maps.newConcurrentMap();
    
    @Unique
    private Map<String, Boolean> lightspeed$existencePerServerResource = Maps.newConcurrentMap();
    
    @Unique
    private String lightspeed$versionId;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(CallbackInfo ci) {
        if (!GlobalCache.isEnabled)
            return;

        GlobalCache.add(this);
        try {
            lightspeed$versionId = Minecraft.getInstance().getLaunchedVersion();
        } catch (Exception e) {
            lightspeed$versionId = "unknown";
        }

        lightspeed$existencePerClientResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$versionId + "-client", k -> Maps.newConcurrentMap());
        lightspeed$existencePerServerResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                lightspeed$versionId + "-server", k -> Maps.newConcurrentMap());
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType packType, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
            return;

        Boolean exists = lightspeed$exists(packType, location.toString());

        if (Boolean.FALSE.equals(exists)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getResource", at = @At("RETURN"))
    public void getResourceReturnInjected(PackType packType, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
            return;

        boolean actuallyExists = cir.getReturnValue() != null;
        lightspeed$cacheExists(packType, location.toString(), actuallyExists);
    }

    
    @Unique
    public Boolean lightspeed$exists(PackType packType, String resourceName) {
        if (packType == PackType.CLIENT_RESOURCES)
            return lightspeed$existencePerClientResource.get(resourceName);
        return lightspeed$existencePerServerResource.get(resourceName);
    }

    
    @Unique
    public void lightspeed$cacheExists(PackType packType, String resourceName, boolean exists) {
        if (packType == PackType.CLIENT_RESOURCES)
            lightspeed$existencePerClientResource.put(resourceName, exists);
        else
            lightspeed$existencePerServerResource.put(resourceName, exists);
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        if (lightspeed$versionId != null) {
            CacheUtil.persist(lightspeed$existencePerClientResource, new File(HAS_RESOURCE_CACHE_DIR.getPath(), lightspeed$versionId + "-client.ser"));
            CacheUtil.persist(lightspeed$existencePerServerResource, new File(HAS_RESOURCE_CACHE_DIR.getPath(), lightspeed$versionId + "-server.ser"));
        }
        lightspeed$existencePerClientResource.clear();
        lightspeed$existencePerServerResource.clear();
    }
}
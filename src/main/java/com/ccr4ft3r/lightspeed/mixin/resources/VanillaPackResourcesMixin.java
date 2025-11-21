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
    
    private Map<String, Boolean> existencePerClientResource = Maps.newConcurrentMap();
    
    private Map<String, Boolean> existencePerServerResource = Maps.newConcurrentMap();
    
    private String versionId;
    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(CallbackInfo ci) {
        if (!GlobalCache.isEnabled)
            return;

        GlobalCache.add(this);
        try {
            versionId = Minecraft.getInstance().getLaunchedVersion();
        } catch (Exception e) {
            versionId = "unknown";
        }

        existencePerClientResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                versionId + "-client", k -> Maps.newConcurrentMap());
        existencePerServerResource = PERSISTED_EXISTENCES_BY_MOD.computeIfAbsent(
                versionId + "-server", k -> Maps.newConcurrentMap());
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType packType, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
            return;

        Boolean exists = exists(packType, location.toString());

        if (Boolean.FALSE.equals(exists)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getResource", at = @At("RETURN"))
    public void getResourceReturnInjected(PackType packType, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
            return;

        boolean actuallyExists = cir.getReturnValue() != null;
        cacheExists(packType, location.toString(), actuallyExists);
    }

    
    public Boolean exists(PackType packType, String resourceName) {
        if (packType == PackType.CLIENT_RESOURCES)
            return existencePerClientResource.get(resourceName);
        return existencePerServerResource.get(resourceName);
    }

    
    public void cacheExists(PackType packType, String resourceName, boolean exists) {
        if (packType == PackType.CLIENT_RESOURCES)
            existencePerClientResource.put(resourceName, exists);
        else
            existencePerServerResource.put(resourceName, exists);
    }

    @Override
    public void persistAndClearCache() {
        if (versionId != null) {
            CacheUtil.persist(existencePerClientResource, new File(HAS_RESOURCE_CACHE_DIR.getPath(), versionId + "-client.ser"));
            CacheUtil.persist(existencePerServerResource, new File(HAS_RESOURCE_CACHE_DIR.getPath(), versionId + "-server.ser"));
        }
        existencePerClientResource.clear();
        existencePerServerResource.clear();
    }
}
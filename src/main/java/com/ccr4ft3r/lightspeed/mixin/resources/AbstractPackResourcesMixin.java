package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.IPackResources;
import com.google.common.collect.Maps;
import net.minecraft.server.packs.AbstractPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(AbstractPackResources.class)
public abstract class AbstractPackResourcesMixin implements IPackResources {
    
    private Map<String, Boolean> existenceByResource = Maps.newConcurrentMap();

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(String name, boolean isBuiltin, CallbackInfo ci) {
        if (GlobalCache.isEnabled)
            GlobalCache.add(this);
    }

    @Override
    public void persistAndClearCache() {
        existenceByResource.clear();
    }

    @Override
    public void setExistenceByResource(Map<String, Boolean> existenceByResource) {
        this.existenceByResource = existenceByResource;
    }

    @Override
    public Map<String, Boolean> getExistenceByResource() {
        return existenceByResource;
    }
}
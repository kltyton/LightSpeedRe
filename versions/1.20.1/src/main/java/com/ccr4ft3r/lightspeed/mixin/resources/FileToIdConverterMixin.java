package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.resource.ResourceListingCache;
import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(FileToIdConverter.class)
public abstract class FileToIdConverterMixin {
    @Shadow @Final private String prefix;
    @Shadow @Final private String extension;

    @Inject(method = "listMatchingResources", at = @At("HEAD"), cancellable = true)
    private void lightspeed$reuseReloadListing(ResourceManager resources,
            CallbackInfoReturnable<Map<ResourceLocation, Resource>> result) {
        if (GlobalCache.isEnabled && resources instanceof ResourceListingCache cache) {
            result.setReturnValue(cache.lightspeed$listMatchingResources(prefix, extension));
        }
    }
}

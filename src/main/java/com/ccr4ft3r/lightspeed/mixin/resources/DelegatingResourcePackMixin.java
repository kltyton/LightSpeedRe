package com.ccr4ft3r.lightspeed.mixin.resources;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.DelegatingPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

@Mixin(DelegatingPackResources.class)
public abstract class DelegatingResourcePackMixin {
    @Shadow
    protected abstract List<PackResources> getCandidatePacks(PackType type, ResourceLocation location);

    @Inject(method = "getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;", at = @At(value = "HEAD"), cancellable = true)
    public void getResourceHeadInjected(PackType type, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!GlobalCache.isEnabled)
            return;
        IoSupplier<InputStream> result = getCandidatePacks(type, location)
                .parallelStream()
                .map(pack -> pack.getResource(type, location))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        cir.setReturnValue(result);
    }
}
package com.ccr4ft3r.lightspeed.mixin.misc;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

@Mixin(value = ResourcePackLoader.class, remap = false)
public abstract class ResourcePackLoaderMixin {

    @Inject(method = "createPackForMod", at = @At("RETURN"), remap = false, cancellable = true)
    private static void createPackForModReturnInjected(IModFileInfo mf, CallbackInfoReturnable<Pack.ResourcesSupplier> cir) {
        if (!GlobalCache.isEnabled)
            return;
        Pack.ResourcesSupplier delegate = cir.getReturnValue();
        cir.setReturnValue(new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(net.minecraft.server.packs.PackLocationInfo location) {
                PackResources resources = delegate.openPrimary(location);
                lightspeed$preparePackResources(resources, mf);
                return resources;
            }

            @Override
            public PackResources openFull(net.minecraft.server.packs.PackLocationInfo location, Pack.Metadata metadata) {
                PackResources resources = delegate.openFull(location, metadata);
                lightspeed$preparePackResources(resources, mf);
                return resources;
            }
        });
    }

    private static void lightspeed$preparePackResources(PackResources resources, IModFileInfo modFileInfo) {
        PackResources primary = lightspeed$getPrimaryResources(resources);
        if (!(primary instanceof IPathResourcePack lightspeedPack)) {
            return;
        }
        lightspeedPack.lightspeed$setModFile(modFileInfo.getFile());
        lightspeedPack.lightspeed$startAsyncPreload();
    }

    private static PackResources lightspeed$getPrimaryResources(PackResources resources) {
        if (!(resources instanceof CompositePackResources)) {
            return resources;
        }
        try {
            Field field = CompositePackResources.class.getDeclaredField("primaryPackResources");
            field.setAccessible(true);
            Object value = field.get(resources);
            if (value instanceof PackResources primary) {
                return primary;
            }
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException ignored) {
            // If the composite layout changes, skip this optimization rather than touching overlay packs.
        }
        return null;
    }
}

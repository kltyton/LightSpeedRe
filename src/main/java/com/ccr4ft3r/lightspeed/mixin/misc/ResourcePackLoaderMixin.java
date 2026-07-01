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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
        Set<PackResources> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        lightspeed$preparePackResources(resources, modFileInfo, visited);
    }

    private static void lightspeed$preparePackResources(PackResources resources, IModFileInfo modFileInfo, Set<PackResources> visited) {
        if (resources == null || !visited.add(resources)) {
            return;
        }
        if (resources instanceof IPathResourcePack lightspeedPack) {
            lightspeedPack.lightspeed$setModFile(modFileInfo.getFile());
            lightspeedPack.lightspeed$startAsyncPreload();
            return;
        }
        if (!(resources instanceof CompositePackResources)) {
            return;
        }
        for (Field field : resources.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(resources);
                if (value instanceof PackResources nested) {
                    lightspeed$preparePackResources(nested, modFileInfo, visited);
                } else if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof PackResources nested) {
                            lightspeed$preparePackResources(nested, modFileInfo, visited);
                        }
                    }
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Composite internals are an optimization path; failing to inspect them should not break loading.
            }
        }
    }
}

package com.ccr4ft3r.lightspeed.mixin.misc;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.IPathResourcePack;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.resource.PathPackResources;
import net.minecraftforge.resource.ResourcePackLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ResourcePackLoader.class, remap = false)
public abstract class ResourcePackLoaderMixin {

    @Inject(method = "createPackForMod", at = @At("RETURN"), remap = false)
    private static void createPackForModReturnInjected(IModFileInfo mf, CallbackInfoReturnable<PathPackResources> cir) {
        if (!GlobalCache.isEnabled)
            return;
        PathPackResources resourcePack = cir.getReturnValue();
        if (!(resourcePack instanceof IPathResourcePack lightspeedPack))
            return;
        lightspeedPack.lightspeed$setModFile(mf.getFile());
        lightspeedPack.lightspeed$startAsyncPreload();
    }
}

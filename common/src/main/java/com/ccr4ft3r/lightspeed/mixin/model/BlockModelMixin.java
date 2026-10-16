package com.ccr4ft3r.lightspeed.mixin.model;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(BlockModel.class)
public abstract class BlockModelMixin {
    @Unique
    private Map<String, Material> lightspeed$materialCache;

    @Inject(method = "getMaterial", at = @At("HEAD"), cancellable = true)
    public void getMaterialHeadInjected(String name, CallbackInfoReturnable<Material> cir) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheMaterials) {
            if (this.lightspeed$materialCache == null) {
            } else {
                Material cached = this.lightspeed$materialCache.get(name);
                if (cached != null) {
                    cir.setReturnValue(cached);
                }
            }
        }
    }

    @Inject(method = "getMaterial", at = @At("RETURN"))
    public void getMaterialReturnInjected(String name, CallbackInfoReturnable<Material> cir) {
        if (GlobalCache.isEnabled && GlobalCache.shouldCacheMaterials && cir.getReturnValue() != null) {
            if (this.lightspeed$materialCache == null) {
                this.lightspeed$materialCache = new HashMap<>();
            }
            this.lightspeed$materialCache.put(name, cir.getReturnValue());
        }
    }
}
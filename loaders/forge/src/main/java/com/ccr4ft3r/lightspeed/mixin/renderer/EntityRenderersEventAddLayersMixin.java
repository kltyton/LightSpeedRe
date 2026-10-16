package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.client.renderer.LazyEntityRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Mixin(value = EntityRenderersEvent.AddLayers.class, remap = false)
public abstract class EntityRenderersEventAddLayersMixin {
    @Redirect(
            method = {"getSkin", "getRenderer"},
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;", remap = false),
            remap = false
    )
    private Object lightspeed$resolveLazyRenderer(Map<?, ?> renderers, Object key) {
        Object renderer = renderers.get(key);
        return renderer instanceof EntityRenderer<?> entityRenderer ? LazyEntityRenderer.unwrap(entityRenderer) : renderer;
    }
}

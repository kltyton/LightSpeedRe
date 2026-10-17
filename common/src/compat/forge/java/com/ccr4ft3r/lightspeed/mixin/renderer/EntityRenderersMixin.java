package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.client.renderer.LazyEntityRenderer;
import com.ccr4ft3r.lightspeed.client.renderer.LazyRendererMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.BiConsumer;

/** Defers renderer construction while exposing native delegates to renderer consumers. */
@Mixin(EntityRenderers.class)
public abstract class EntityRenderersMixin {

    @Inject(method = "createEntityRenderers", at = @At("RETURN"), cancellable = true)
    private static void lightspeed$wrapEntityRendererMap(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<EntityType<?>, EntityRenderer<?>>> cir) {
        cir.setReturnValue(new LazyRendererMap<>(cir.getReturnValue()));
    }

    @Inject(method = "createPlayerRenderers", at = @At("RETURN"), cancellable = true)
    private static void lightspeed$wrapPlayerRendererMap(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<String, EntityRenderer<? extends Player>>> cir) {
        cir.setReturnValue(new LazyRendererMap<>(cir.getReturnValue()));
    }

    @WrapOperation(
            method = "createEntityRenderers",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")
    )
    private static void lightspeed$deferEntityRenderers(
            Map<EntityType<?>, EntityRendererProvider<?>> providers,
            BiConsumer<EntityType<?>, EntityRendererProvider<?>> action,
            Operation<Void> original) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(providers, action);
            return;
        }
        original.call(providers, (BiConsumer<EntityType<?>, EntityRendererProvider<?>>) (entityType, provider) -> {
            ResourceLocation rendererId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            @SuppressWarnings({"rawtypes", "unchecked"})
            EntityRendererProvider<?> deferred = lazyProvider((EntityRendererProvider) provider,
                    rendererId == null ? entityType.toString() : rendererId.toString());
            action.accept(entityType, deferred);
        });
    }

    @WrapOperation(
            method = "createPlayerRenderers",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")
    )
    private static void lightspeed$deferPlayerRenderers(
            Map<String, EntityRendererProvider<AbstractClientPlayer>> providers,
            BiConsumer<String, EntityRendererProvider<AbstractClientPlayer>> action,
            Operation<Void> original) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(providers, action);
            return;
        }
        original.call(providers, (BiConsumer<String, EntityRendererProvider<AbstractClientPlayer>>) (model, provider) ->
                action.accept(model, lazyProvider(provider, "minecraft:player/" + model)));
    }

    private static <T extends Entity> EntityRendererProvider<T> lazyProvider(
            EntityRendererProvider<T> provider, String rendererId) {
        // Fabric's factory callbacks inspect and cast the actual renderer before the map is returned.
        if (ModList.get().isLoaded("fabric_rendering_v1")) {
            return provider;
        }
        return context -> new LazyEntityRenderer<>(context, provider, rendererId);
    }


}

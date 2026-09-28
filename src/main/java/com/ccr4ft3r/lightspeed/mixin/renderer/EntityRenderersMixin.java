package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.compat.ResourceReloadFailureGuard;
import com.ccr4ft3r.lightspeed.client.renderer.LazyEntityRenderer;
import com.ccr4ft3r.lightspeed.client.renderer.LazyRendererMap;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Keeps one broken renderer provider from aborting the complete entity renderer reload. */
@Mixin(EntityRenderers.class)
public abstract class EntityRenderersMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

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
    private static void lightspeed$isolateEntityRendererFailure(
            Map<EntityType<?>, EntityRendererProvider<?>> providers,
            BiConsumer<EntityType<?>, EntityRendererProvider<?>> action,
            Operation<Void> original,
            @Local(argsOnly = true) EntityRendererProvider.Context context,
            @Local ImmutableMap.Builder<EntityType<?>, EntityRenderer<?>> builder) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(providers, action);
            return;
        }

        BiConsumer<EntityType<?>, EntityRendererProvider<?>> guardedAction = (entityType, provider) -> {
            try {
                ResourceLocation rendererId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
                @SuppressWarnings({"rawtypes", "unchecked"})
                EntityRendererProvider<?> lazyProvider = lazyProvider(
                        (EntityRendererProvider) provider,
                        rendererId == null ? entityType.toString() : rendererId.toString());
                action.accept(entityType, lazyProvider);
            } catch (RuntimeException failure) {
                ResourceLocation rendererId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
                String rendererNamespace = rendererId == null ? null : rendererId.getNamespace();
                if (!ResourceReloadFailureGuard.shouldIsolateRendererFailure(rendererNamespace, provider, failure)) {
                    throw failure;
                }
                try {
                    builder.put(entityType, new NoopRenderer<Entity>(context));
                } catch (RuntimeException fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                    throw failure;
                }
                lightspeed$logFallback(rendererId == null ? entityType.toString() : rendererId.toString(), provider, failure);
            }
        };
        original.call(providers, guardedAction);
    }

    @WrapOperation(
            method = "createPlayerRenderers",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")
    )
    private static void lightspeed$isolatePlayerRendererFailure(
            Map<String, EntityRendererProvider<AbstractClientPlayer>> providers,
            BiConsumer<String, EntityRendererProvider<AbstractClientPlayer>> action,
            Operation<Void> original,
            @Local(argsOnly = true) EntityRendererProvider.Context context,
            @Local ImmutableMap.Builder<String, EntityRenderer<? extends Player>> builder) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            original.call(providers, action);
            return;
        }

        BiConsumer<String, EntityRendererProvider<AbstractClientPlayer>> guardedAction = (model, provider) -> {
            try {
                action.accept(model, lazyProvider(provider, "minecraft:player/" + model));
            } catch (RuntimeException failure) {
                if (!ResourceReloadFailureGuard.shouldIsolateRendererFailure("minecraft", provider, failure)) {
                    throw failure;
                }
                try {
                    builder.put(model, new NoopRenderer<AbstractClientPlayer>(context));
                } catch (RuntimeException fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                    throw failure;
                }
                lightspeed$logFallback("minecraft:player/" + model, provider, failure);
            }
        };
        original.call(providers, guardedAction);
    }

    private static <T extends Entity> EntityRendererProvider<T> lazyProvider(
            EntityRendererProvider<T> provider, String rendererId) {
        return context -> new LazyEntityRenderer<>(context, provider, rendererId);
    }

    private static void lightspeed$logFallback(String rendererId, EntityRendererProvider<?> provider, RuntimeException failure) {
        if (LOGGED_FAILURES.add(rendererId)) {
            LOGGER.warn("Lightspeed replaced failed entity renderer {} from {} with the vanilla no-op renderer: {}",
                    rendererId, provider.getClass().getName(), failure.toString());
            LOGGER.debug("Entity renderer fallback details for {}", rendererId, failure);
        }
    }
}

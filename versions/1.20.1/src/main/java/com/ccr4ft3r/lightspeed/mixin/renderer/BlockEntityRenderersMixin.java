package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.ccr4ft3r.lightspeed.client.renderer.LazyBlockEntityRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.function.BiConsumer;

@Mixin(BlockEntityRenderers.class)
public abstract class BlockEntityRenderersMixin {
    @WrapOperation(
            method = "createEntityRenderers",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V")
    )
    private static void lightspeed$lazilyCreateRenderers(
            Map<BlockEntityType<?>, BlockEntityRendererProvider<?>> providers,
            BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?>> action,
            Operation<Void> original) {
        BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?>> lazyAction = (type, provider) -> {
            String id = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
            @SuppressWarnings({"rawtypes", "unchecked"})
            BlockEntityRendererProvider<?> lazy = lazyProvider((BlockEntityRendererProvider) provider, id);
            action.accept(type, lazy);
        };
        original.call(providers, lazyAction);
    }

    private static <T extends BlockEntity> BlockEntityRendererProvider<T> lazyProvider(
            BlockEntityRendererProvider<T> provider, String rendererId) {
        return context -> new LazyBlockEntityRenderer<>(context, provider, rendererId);
    }
}

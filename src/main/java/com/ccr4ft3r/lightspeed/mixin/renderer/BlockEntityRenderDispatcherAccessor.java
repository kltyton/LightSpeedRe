package com.ccr4ft3r.lightspeed.mixin.renderer;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Exposes registered wrappers to static model consumers that have no block entity instance. */
@Mixin(BlockEntityRenderDispatcher.class)
public interface BlockEntityRenderDispatcherAccessor {
    @Accessor("renderers")
    Map<BlockEntityType<?>, BlockEntityRenderer<?>> lightspeed$getRenderers();
}

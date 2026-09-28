package com.ccr4ft3r.lightspeed.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
    @Accessor("shadowRadius")
    float lightspeed$shadowRadius();

    @Accessor("shadowStrength")
    float lightspeed$shadowStrength();

    @Invoker("getSkyLightLevel")
    int lightspeed$getSkyLightLevel(Entity entity, BlockPos position);

    @Invoker("getBlockLightLevel")
    int lightspeed$getBlockLightLevel(Entity entity, BlockPos position);
}

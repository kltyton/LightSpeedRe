package com.ccr4ft3r.lightspeed.mixin.model;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.IdentityHashMap;

@Mixin(ModelManager.class)
public abstract class ModelManagerPreallocMixin {
    @WrapOperation(method = "loadModels", at = @At(value = "NEW", target = "()Ljava/util/IdentityHashMap;"))
    private IdentityHashMap<BlockState, BakedModel> lightspeed$presizeBlockStateModelMap(
            Operation<IdentityHashMap<BlockState, BakedModel>> original) {
        int stateCount = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            stateCount += block.getStateDefinition().getPossibleStates().size();
        }
        if (stateCount <= 32) {
            return original.call();
        }
        return new IdentityHashMap<>(stateCount + 64);
    }
}

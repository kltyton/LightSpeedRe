package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.IdMapperCapacity;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pre-sizes NeoForge's global blockstate ID mapper before freezing all block states. */
@Mixin(targets = "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks", remap = false)
public abstract class NeoForgeBlockCallbacksMixin {
    @Inject(method = "onBake(Lnet/minecraft/core/Registry;)V", at = @At("HEAD"), require = 0)
    private void lightspeed$preallocateBlockStateIds(Registry<Block> registry, CallbackInfo ci) {
        IdMapper<BlockState> mapper = GameData.getBlockStateIDMap();
        if (mapper instanceof IdMapperCapacity capacity) {
            int totalStates = 0;
            for (Block block : registry) {
                totalStates += block.getStateDefinition().getPossibleStates().size();
            }
            capacity.lightspeed$ensureCapacity(totalStates);
        }
    }
}

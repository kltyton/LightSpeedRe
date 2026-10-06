package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.registry.RegistryHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// Expose the immutable registry identity of Forge's annotated field holder.
@Mixin(targets = "net.minecraftforge.registries.ObjectHolderRef", remap = false)
public abstract class ObjectHolderRefMixin implements RegistryHolder {
    @Shadow @Final private ForgeRegistry<?> registry;

    @Override
    public ResourceLocation lightspeed$validatedRegistryName() {
        return registry.getRegistryName();
    }
}

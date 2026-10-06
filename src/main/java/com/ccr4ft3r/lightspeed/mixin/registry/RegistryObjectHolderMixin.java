package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.registry.RegistryHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

// Pending validation stays on the full path, including a missing registry's first error.
// Mixin 0.8.5's ASM annotation-processing path parses anonymous-class fields as method descriptors.
@Pseudo
@Mixin(targets = "net.minecraftforge.registries.RegistryObject$1", remap = false)
public abstract class RegistryObjectHolderMixin implements RegistryHolder {
    @Shadow private boolean registryExists;
    @Shadow private boolean invalidRegistry;
    @Shadow @Final private ResourceLocation val$registryName;
    @Shadow @Final private RegistryObject<?> this$0;

    @Override
    public ResourceLocation lightspeed$validatedRegistryName() {
        return registryExists || invalidRegistry || ((RegistryObjectAccessor) (Object) this$0).lightspeed$isOptionalRegistry()
                ? val$registryName : null;
    }
}

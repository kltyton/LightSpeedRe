package com.ccr4ft3r.lightspeed.mixin.registry;

import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Optional references intentionally do not perform the registry-existence validation.
@Mixin(value = RegistryObject.class, remap = false)
public interface RegistryObjectAccessor {
    @Accessor("optionalRegistry")
    boolean lightspeed$isOptionalRegistry();
}

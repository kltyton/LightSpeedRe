package com.ccr4ft3r.lightspeed.startup.registry;

import net.minecraft.resources.ResourceLocation;

public interface RegistryHolder {
    // Null keeps a not-yet-validated reference in every registry's dispatch.
    ResourceLocation lightspeed$validatedRegistryName();
}

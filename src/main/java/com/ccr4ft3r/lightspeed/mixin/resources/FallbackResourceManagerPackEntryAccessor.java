package com.ccr4ft3r.lightspeed.mixin.resources;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.packs.resources.FallbackResourceManager$PackEntry")
public interface FallbackResourceManagerPackEntryAccessor {
    @Invoker("name")
    String lightspeed$name();

    @Invoker("resources")
    PackResources lightspeed$resources();

    @Invoker("isFiltered")
    boolean lightspeed$isFiltered(ResourceLocation location);
}

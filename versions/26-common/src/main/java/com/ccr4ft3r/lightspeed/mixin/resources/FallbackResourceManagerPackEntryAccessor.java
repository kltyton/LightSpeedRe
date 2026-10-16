package com.ccr4ft3r.lightspeed.mixin.resources;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

@Mixin(targets = "net.minecraft.server.packs.resources.FallbackResourceManager$PackEntry")
public interface FallbackResourceManagerPackEntryAccessor {
    @Invoker("name")
    String lightspeed$name();

    @Invoker("resources")
    PackResources lightspeed$resources();

    @Invoker("filter")
    Predicate<Identifier> lightspeed$filter();

    @Invoker("isFiltered")
    boolean lightspeed$isFiltered(Identifier location);
}

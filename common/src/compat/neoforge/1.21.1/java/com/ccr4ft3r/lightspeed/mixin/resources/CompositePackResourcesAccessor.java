package com.ccr4ft3r.lightspeed.mixin.resources;

import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Direct accessor to {@link CompositePackResources#primaryPackResources} avoiding runtime reflection. */
@Mixin(CompositePackResources.class)
public interface CompositePackResourcesAccessor {
    @Accessor("primaryPackResources")
    PackResources lightspeed$getPrimaryPackResources();
}

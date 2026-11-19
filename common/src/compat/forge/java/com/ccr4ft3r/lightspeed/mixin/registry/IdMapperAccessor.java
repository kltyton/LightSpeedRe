package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.IdMapperListAccess;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(IdMapper.class)
public interface IdMapperAccessor extends IdMapperListAccess {
    @Override
    @Accessor("idToT")
    List<?> lightspeed$getIdToT();
}

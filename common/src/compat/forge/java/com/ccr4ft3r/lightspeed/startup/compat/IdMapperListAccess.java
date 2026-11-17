package com.ccr4ft3r.lightspeed.startup.compat;

import java.util.List;

/**
 * Non-MC duck bridge implemented via Mixin on {@code net.minecraft.core.IdMapper}
 * so Forge-layer parallel helpers can read the backing state table without reflection.
 */
public interface IdMapperListAccess {
    List<?> lightspeed$getIdToT();
}

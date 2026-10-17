package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.server.packs.PackResources;

import java.util.function.Predicate;

/** Read-only metadata supplied by native fallback entry accessors. */
public interface ResourcePackEntry {
    String lightspeed$name();
    PackResources lightspeed$resources();
    Predicate<?> lightspeed$filter();
}

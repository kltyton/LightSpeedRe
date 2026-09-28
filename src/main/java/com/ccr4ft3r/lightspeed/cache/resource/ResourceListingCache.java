package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.util.Map;

public interface ResourceListingCache {
    Map<ResourceLocation, Resource> lightspeed$listMatchingResources(String path, String extension);
}

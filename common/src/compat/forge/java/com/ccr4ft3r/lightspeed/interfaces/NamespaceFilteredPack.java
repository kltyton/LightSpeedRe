package com.ccr4ft3r.lightspeed.interfaces;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

import java.util.Collection;
import java.util.List;

/** Filters delegating pack children by namespace during resource-stack lookups. */
public interface NamespaceFilteredPack {
    List<PackResources> lightspeed$candidatesForStack(PackType type, String namespace, Collection<PackResources> children);
}

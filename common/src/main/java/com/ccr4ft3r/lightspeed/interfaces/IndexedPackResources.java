package com.ccr4ft3r.lightspeed.interfaces;

import net.minecraft.server.packs.PackType;

/** Membership and directory presence in a loaded pack snapshot; null leaves lookup to the original pack. */
public interface IndexedPackResources {
    Boolean lightspeed$containsIndexed(String type, String namespace, String path);

    /** Returns {@code true}/{@code false} when a loaded snapshot proves presence under {@code path}, else {@code null}. */
    default Boolean lightspeed$hasAnyUnder(PackType type, String namespace, String path) {
        return null;
    }
}

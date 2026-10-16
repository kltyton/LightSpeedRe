package com.ccr4ft3r.lightspeed.interfaces;

/** Membership in a loaded pack snapshot; null leaves lookup to the original pack. */
public interface IndexedPackResources {
    Boolean lightspeed$containsIndexed(String type, String namespace, String path);
}

package com.ccr4ft3r.lightspeed.cache.resource;

import java.util.zip.ZipFile;

/** Shared ZIP entry index carrier attached to {@code FilePackResources.SharedZipFileAccess}. */
public interface SharedZipIndexHolder {
    ZipResourceIndex lightspeed$getOrCreateZipIndex(ZipFile zip);

    ZipResourceIndex lightspeed$getLoadedZipIndex();
}

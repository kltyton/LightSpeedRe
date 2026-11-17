package com.ccr4ft3r.lightspeed.bootstrap.runtime.scan;

/** Loader scan metadata types used before the game module layer exists. */
public final class ScanRuntimeTypes {
    public static final String SCAN_DATA = "net.neoforged.neoforgespi.language.ModFileScanData";
    public static final String CLASS_VISITOR = "net.neoforged.fml.loading.modscan.ModClassVisitor";
    public static final String ENUM_HOLDER = "net.neoforged.fml.loading.modscan.ModAnnotation$EnumHolder";
    public static final String ENUM_DESCRIPTOR_GETTER = "desc";
    public static final String ENUM_VALUE_GETTER = "value";

    private ScanRuntimeTypes() { }
}

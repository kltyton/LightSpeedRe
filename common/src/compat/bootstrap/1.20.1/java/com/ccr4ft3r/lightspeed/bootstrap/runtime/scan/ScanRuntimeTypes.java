package com.ccr4ft3r.lightspeed.bootstrap.runtime.scan;

/** Loader scan metadata types used before the game module layer exists. */
public final class ScanRuntimeTypes {
    public static final String SCAN_DATA = "net.minecraftforge.forgespi.language.ModFileScanData";
    public static final String CLASS_VISITOR = "net.minecraftforge.fml.loading.moddiscovery.ModClassVisitor";
    public static final String ENUM_HOLDER = "net.minecraftforge.fml.loading.moddiscovery.ModAnnotation$EnumHolder";
    public static final String ENUM_DESCRIPTOR_GETTER = "getDesc";
    public static final String ENUM_VALUE_GETTER = "getValue";

    private ScanRuntimeTypes() { }
}

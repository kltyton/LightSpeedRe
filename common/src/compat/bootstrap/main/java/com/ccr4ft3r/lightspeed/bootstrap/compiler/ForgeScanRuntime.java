package com.ccr4ft3r.lightspeed.bootstrap.compiler;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

final class ForgeScanRuntime {
    private static final String MOD_CLASS_VISITOR =
            "net.minecraftforge.fml.loading.moddiscovery.ModClassVisitor";
    private static final String MOD_FILE_SCAN_DATA =
            "net.minecraftforge.forgespi.language.ModFileScanData";

    private final Constructor<?> scanDataConstructor;
    private final Constructor<?> visitorConstructor;
    private final Constructor<?> readerConstructor;
    private final Method accept;
    private final Method buildData;
    private final Method getClasses;
    private final Method getAnnotations;
    private final int readerFlags;

    private ForgeScanRuntime(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> scanData = Class.forName(MOD_FILE_SCAN_DATA, true, loader);
        Class<?> visitor = Class.forName(MOD_CLASS_VISITOR, true, loader);
        Class<?> classReader = Class.forName(asmClass("ClassReader"), true, loader);
        Class<?> classVisitor = Class.forName(asmClass("ClassVisitor"), true, loader);

        this.scanDataConstructor = scanData.getConstructor();
        this.visitorConstructor = visitor.getConstructor();
        this.readerConstructor = classReader.getConstructor(InputStream.class);
        this.accept = classReader.getMethod("accept", classVisitor, int.class);
        this.buildData = visitor.getMethod("buildData", Set.class, Set.class);
        this.getClasses = scanData.getMethod("getClasses");
        this.getAnnotations = scanData.getMethod("getAnnotations");
        this.readerFlags = classReader.getField("SKIP_CODE").getInt(null)
                | classReader.getField("SKIP_DEBUG").getInt(null)
                | classReader.getField("SKIP_FRAMES").getInt(null);
    }

    static ForgeScanRuntime load() throws ReflectiveOperationException {
        return new ForgeScanRuntime(ScanPackCompiler.class.getClassLoader());
    }

    private static String asmClass(String simpleName) {
        return new StringBuilder("org.objectweb").append(".asm.").append(simpleName).toString();
    }

    Object newScanData() throws ReflectiveOperationException {
        return scanDataConstructor.newInstance();
    }

    void scanClass(InputStream input, Object scanData) throws ReflectiveOperationException {
        Object visitor = visitorConstructor.newInstance();
        Object reader = readerConstructor.newInstance(input);
        accept.invoke(reader, visitor, readerFlags);
        buildData.invoke(visitor, getClasses.invoke(scanData), getAnnotations.invoke(scanData));
    }

    int classCount(Object scanData) throws ReflectiveOperationException {
        return ((Set<?>) getClasses.invoke(scanData)).size();
    }
}

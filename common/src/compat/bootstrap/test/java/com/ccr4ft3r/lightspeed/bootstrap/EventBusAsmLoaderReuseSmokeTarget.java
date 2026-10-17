package com.ccr4ft3r.lightspeed.bootstrap;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class EventBusAsmLoaderReuseSmokeTarget {
    private static final String LOADER_CLASS =
            "net.minecraftforge.eventbus.ClassLoaderFactory$ASMClassLoader";

    private EventBusAsmLoaderReuseSmokeTarget() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightspeed-eventbus-loader-");
        try {
            byte[] restoredBytes = compile(directory, "fixture.predefined.Restored");
            byte[] freshBytes = compile(directory, "fixture.newlydefined.Fresh");
            Class<?> loaderType = Class.forName(LOADER_CLASS);
            var constructor = loaderType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object loader = constructor.newInstance();
            Method define = loaderType.getDeclaredMethod("define", String.class, byte[].class);
            define.setAccessible(true);
            Class<?> restored = invokeDefine(define, loader, "fixture.predefined.Restored", restoredBytes);
            Class<?> reused = invokeDefine(define, loader, "fixture.predefined.Restored", restoredBytes);
            require(reused == restored, "pre-defined EventBus wrapper identity was not reused");
            Class<?> fresh = invokeDefine(define, loader, "fixture.newlydefined.Fresh", freshBytes);
            require(fresh.getName().equals("fixture.newlydefined.Fresh"), "new wrapper name was not defined");
            require(fresh.getClassLoader() == loader, "new wrapper used the wrong loader");
            System.out.println("EVENTBUS_ASM_LOADER_REUSE_OK");
        } finally {
            deleteTree(directory);
        }
    }

    private static byte[] compile(Path directory, String className) throws Exception {
        int separator = className.lastIndexOf('.');
        String packageName = className.substring(0, separator);
        String simpleName = className.substring(separator + 1);
        Path source = directory.resolve("src/" + className.replace('.', '/') + ".java");
        Path classes = directory.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, "package " + packageName + "; public final class " + simpleName + " { }\n");
        var compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "smoke test requires a JDK compiler");
        int exitCode = compiler.run(null, System.out, System.err, "-d", classes.toString(), source.toString());
        require(exitCode == 0, "fixture compilation failed with exit code " + exitCode);
        return Files.readAllBytes(classes.resolve(className.replace('.', '/') + ".class"));
    }

    private static Class<?> invokeDefine(Method define, Object loader, String name, byte[] bytes) throws Exception {
        try {
            return (Class<?>) define.invoke(loader, name, bytes);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception checked) throw checked;
            throw exception;
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        java.util.List<Path> children;
        try (var paths = Files.walk(root)) {
            children = paths.filter(path -> !path.equals(root)).sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : children) Files.deleteIfExists(path);
        Files.deleteIfExists(root);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

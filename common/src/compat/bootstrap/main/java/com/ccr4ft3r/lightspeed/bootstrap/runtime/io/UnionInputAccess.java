package com.ccr4ft3r.lightspeed.bootstrap.runtime.io;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.BiFunction;

public final class UnionInputAccess {
    private static final ClassValue<BiFunction<FileSystem, Path, Optional<Path>>> SELECTORS = new ClassValue<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected BiFunction<FileSystem, Path, Optional<Path>> computeValue(Class<?> type) {
            // Union's filtered physical-path selection has no public API, and BOOT classes are already defined.
            RuntimeModuleAccess.openToAgent(type);
            try {
                Class<?> pathType = Class.forName("cpw.mods.niofs.union.UnionPath", false, type.getClassLoader());
                MethodHandle selection = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findVirtual(type, "findFirstFiltered", MethodType.methodType(Optional.class, pathType));
                return MethodHandleProxies.asInterfaceInstance(BiFunction.class, selection);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot access the running Union filesystem path selector", exception);
            }
        }
    };

    private UnionInputAccess() { }

    public static InputStream open(Path path, OpenOption[] options) throws IOException {
        for (OpenOption option : options) {
            if (option == StandardOpenOption.APPEND || option == StandardOpenOption.WRITE) {
                throw new UnsupportedOperationException("'" + option + "' not allowed");
            }
            if (option != StandardOpenOption.READ) throw new UnsupportedOperationException();
        }
        if (!path.getClass().getName().equals("cpw.mods.niofs.union.UnionPath")) {
            throw new UnsupportedOperationException();
        }
        FileSystem fileSystem = path.getFileSystem();
        try {
            Path actual = SELECTORS.get(fileSystem.getClass()).apply(fileSystem, path)
                    .orElseThrow(FileNotFoundException::new);
            return Files.newInputStream(actual);
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

}

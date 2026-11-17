package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

/** Identifies JarJar roots through their enclosing archive's existing content identity. */
record NestedJarSource(String parentKey, String locator) {
    private static final ClassValue<Method> TARGET_ACCESS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                // The bootstrap loader cannot link either loader's optional JarJar module directly.
                return type.getMethod("getTarget");
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Unsupported JarJar filesystem API", exception);
            }
        }
    };

    static NestedJarSource resolve(Path root) throws IOException {
        FileSystem filesystem = root.getFileSystem();
        String type = filesystem.getClass().getName();
        if ((!type.equals("net.minecraftforge.jarjar.nio.pathfs.PathFileSystem")
                && !type.equals("net.neoforged.jarjar.nio.pathfs.PathFileSystem"))
                || !root.toAbsolutePath().equals(filesystem.getPath("/"))) {
            return null;
        }
        Path archive;
        try {
            archive = (Path) TARGET_ACCESS.get(filesystem.getClass()).invoke(filesystem);
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Cannot identify the enclosing JarJar archive", exception);
        }
        if (archive.getFileSystem() == filesystem || !Files.isRegularFile(archive)) {
            return null;
        }
        String parentKey = ResourceMembershipIndex.persistentFileKey(archive);
        if (parentKey == null) return null;
        int relative = parentKey.indexOf('\0');
        int identity = parentKey.lastIndexOf('\t', relative);
        if (identity < 0 || relative == parentKey.length() - 1) return null;
        return new NestedJarSource(parentKey,
                parentKey.substring(0, identity) + "!/" + parentKey.substring(relative + 1));
    }
}

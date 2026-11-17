package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Binds neutral indexes to the directory already read by the owning open ZipFile. */
final class ZipDirectoryIdentity {
    private ZipDirectoryIdentity() { }

    static String key(Path path, long size, long modified, JarFile jar) throws IOException {
        Access access = Handles.ACCESS;
        if (access == null) return null;
        Object resource = access.resource.get(jar);
        Object source = access.source.get(resource);
        byte[] directory = (byte[]) access.directory.get(source);
        if (directory == null && jar.size() != 0) throw new IOException("Missing open ZIP directory " + path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("zip-directory-index-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update((path + "\t" + size + "\t" + modified + '\0').getBytes(StandardCharsets.UTF_8));
            if (directory != null) digest.update(directory);
            String key = path + "\t" + HexFormat.of().formatHex(digest.digest()) + "\0index";
            return key.getBytes(StandardCharsets.UTF_8).length <= 64 * 1024 ? key : null;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class Handles {
        private static final Access ACCESS = open();

        private static Access open() {
            // ZipFile exposes no public central-directory view; enumerate through its normal API if unavailable.
            RuntimeModuleAccess.openToAgent(ZipFile.class);
            if (!ZipFile.class.getModule().isOpen("java.util.zip", ZipDirectoryIdentity.class.getModule())) return null;
            try {
                Class<?> resource = ZipFile.class.getDeclaredField("res").getType();
                Class<?> source = resource.getDeclaredField("zsrc").getType();
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return new Access(
                        MethodHandles.privateLookupIn(ZipFile.class, lookup).findVarHandle(ZipFile.class, "res", resource),
                        MethodHandles.privateLookupIn(resource, lookup).findVarHandle(resource, "zsrc", source),
                        MethodHandles.privateLookupIn(source, lookup).findVarHandle(source, "cen", byte[].class));
            } catch (ReflectiveOperationException exception) {
                System.err.println("[Lightspeed] ZIP directory layout unavailable: " + exception);
                return null;
            }
        }
    }

    private record Access(VarHandle resource, VarHandle source, VarHandle directory) { }
}

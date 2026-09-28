package com.ccr4ft3r.lightspeed.bootstrap.compiler;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class PhysicalModScanner {
    private static final int MAX_CLASSES = 100_000;
    private static final long MAX_CLASS_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_TOTAL_CLASS_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_JAR_BYTES = 2L * 1024L * 1024L * 1024L;

    private PhysicalModScanner() {
    }

    static Result scan(Path path, String beforeKey, ForgeScanRuntime runtime) {
        try {
            if (Files.size(path) > MAX_JAR_BYTES) {
                return Result.skipped(path);
            }
            Object scanData = runtime.newScanData();
            int entries = 0;
            long totalClassBytes = 0;
            try (JarFile jar = new JarFile(path.toFile(), false)) {
                if (jar.isMultiRelease()) {
                    return Result.skipped(path);
                }
                Enumeration<JarEntry> jarEntries = jar.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    if (++entries > MAX_CLASSES || entry.getSize() > MAX_CLASS_BYTES) {
                        return Result.skipped(path);
                    }
                    try (BoundedInputStream input = new BoundedInputStream(
                            jar.getInputStream(entry), MAX_CLASS_BYTES)) {
                        runtime.scanClass(input, scanData);
                        totalClassBytes = Math.addExact(totalClassBytes, input.bytesRead());
                    }
                    if (totalClassBytes > MAX_TOTAL_CLASS_BYTES) {
                        return Result.skipped(path);
                    }
                }
            }
            if (runtime.classCount(scanData) != entries) {
                return Result.skipped(path);
            }
            byte[] encoded = ScanMetadataCache.standaloneEncode(scanData);
            String afterKey = ResourceMembershipIndex.physicalJarScanKey(path);
            if (!Objects.equals(beforeKey, afterKey)) {
                return Result.skipped(path);
            }
            return new Result(path, beforeKey, encoded, entries, true);
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Result.skipped(path);
        }
    }

    record Result(Path path, String key, byte[] encoded, int classes, boolean supported) {
        static Result skipped(Path path) {
            return new Result(path, null, null, 0, false);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long bytesRead;

        private BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                increment(count);
            }
            return count;
        }

        private void increment(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > limit) {
                throw new IOException("class entry exceeds scan bound");
            }
        }

        private long bytesRead() {
            return bytesRead;
        }
    }
}

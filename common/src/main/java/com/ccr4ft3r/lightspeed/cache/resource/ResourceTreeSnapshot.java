package com.ccr4ft3r.lightspeed.cache.resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Captures directory membership alongside the files accepted by the original pack's traversal. */
public final class ResourceTreeSnapshot {
    private ResourceTreeSnapshot() {
    }

    public static List<String> read(Path root, BiPredicate<Path, BasicFileAttributes> includeFile) {
        List<String> paths = new ArrayList<>();
        String separator = root.getFileSystem().getSeparator();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    paths.add(root.relativize(directory).toString().replace(separator, "/") + "/");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (includeFile.test(file, attributes)) {
                        paths.add(root.relativize(file).toString().replace(separator, "/"));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    if (file.equals(root) && exception instanceof NoSuchFileException) {
                        return FileVisitResult.TERMINATE;
                    }
                    throw exception;
                }
            });
            return List.copyOf(paths);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot index resource tree " + root, exception);
        }
    }
}

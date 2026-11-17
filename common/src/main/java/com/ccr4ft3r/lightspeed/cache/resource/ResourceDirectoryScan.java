package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Shares directory reads between a namespace prefetch and requests for individual subtrees. */
public final class ResourceDirectoryScan {
    private final Path root;
    private final Map<Path, FutureTask<DirectorySnapshot>> directories = new ConcurrentHashMap<>();

    public ResourceDirectoryScan(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public List<String> scan(String requestedPath) {
        Path requested = root.resolve(requestedPath.replace("/", root.getFileSystem().getSeparator()));
        try {
            DirectorySnapshot first = directory(requested);
            if (!first.exists()) return List.of();
            List<String> paths = new ArrayList<>();
            Deque<Iterator<Entry>> pending = new ArrayDeque<>();
            pending.push(first.entries().iterator());
            while (!pending.isEmpty()) {
                Iterator<Entry> current = pending.peek();
                if (!current.hasNext()) {
                    pending.pop();
                    continue;
                }
                Entry entry = current.next();
                if (entry instanceof FileEntry file) {
                    paths.add(file.name());
                } else if (entry instanceof DirectoryEntry child) {
                    DirectorySnapshot snapshot = directory(child.path());
                    if (!snapshot.exists()) throw new NoSuchFileException(child.path().toString());
                    pending.push(snapshot.entries().iterator());
                }
            }
            return paths;
        } catch (IOException exception) {
            LogUtils.getLogger().error("Failed to list path {}", requested, exception);
            return null;
        } catch (RuntimeException exception) {
            LogUtils.getLogger().warn("Failed to list path {}; falling back to vanilla resource enumeration", requested, exception);
            return null;
        }
    }

    private DirectorySnapshot directory(Path path) throws IOException {
        FutureTask<DirectorySnapshot> task = directories.computeIfAbsent(path,
                directory -> new FutureTask<>(() -> readDirectory(directory)));
        // A requesting thread can claim its subtree even while prefetch is visiting another branch.
        task.run();
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted while listing " + path);
            interrupted.initCause(exception);
            throw interrupted;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException failure) throw failure;
            if (cause instanceof RuntimeException failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw new IOException("Failed to list " + path, cause);
        }
    }

    private DirectorySnapshot readDirectory(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return DirectorySnapshot.MISSING;
        }
        if (attributes.isRegularFile()) {
            return new DirectorySnapshot(true, List.of(new FileEntry(relative(path))));
        }
        if (!attributes.isDirectory()) return new DirectorySnapshot(true, List.of());
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) {
                BasicFileAttributes childAttributes = Files.readAttributes(child, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (childAttributes.isDirectory()) entries.add(new DirectoryEntry(child));
                else if (childAttributes.isRegularFile()) entries.add(new FileEntry(relative(child)));
            }
        }
        return new DirectorySnapshot(true, List.copyOf(entries));
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace(root.getFileSystem().getSeparator(), "/");
    }

    private sealed interface Entry permits FileEntry, DirectoryEntry { }
    private record FileEntry(String name) implements Entry { }
    private record DirectoryEntry(Path path) implements Entry { }
    private record DirectorySnapshot(boolean exists, List<Entry> entries) {
        private static final DirectorySnapshot MISSING = new DirectorySnapshot(false, List.of());
    }
}

package com.ccr4ft3r.lightspeed.bootstrap.runtime.index;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/** Binds Union 3 roots while retaining the filter's original base Path objects. */
public final class UnionRootBinding {
    private static final Set<FileSystem> BOUND = ConcurrentHashMap.newKeySet();

    private UnionRootBinding() { }

    public static void register(Path root, List<Path> basePaths, Object filter, PathFilterTest test) {
        FileSystem filesystem = root.getFileSystem();
        if (BOUND.contains(filesystem)) return;
        Path[] sources = basePaths.toArray(Path[]::new);
        BiPredicate<String, String> adapted = null;
        if (filter != null) {
            Map<String, Path> originals = new HashMap<>();
            for (Path source : sources) {
                String key = ResourceMembershipIndex.normalize(source.toString());
                Path previous = originals.putIfAbsent(key, source);
                if (previous != null && previous != source) {
                    // The string-based index cannot distinguish these two native filter arguments.
                    BootstrapHooks.registerResourceRoot(root, null, null, new Path[0]);
                    BOUND.add(filesystem);
                    return;
                }
            }
            adapted = (entry, base) -> test.test(filter, entry, originals.get(base));
        }
        Path primary = sources.length == 0 ? null : sources[sources.length - 1];
        BootstrapHooks.registerResourceRoot(root, primary, adapted, sources);
        BOUND.add(filesystem);
    }

    @FunctionalInterface
    public interface PathFilterTest {
        boolean test(Object filter, String entry, Path base);
    }
}

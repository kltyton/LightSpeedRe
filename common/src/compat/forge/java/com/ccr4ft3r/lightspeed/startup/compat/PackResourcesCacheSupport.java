package com.ccr4ft3r.lightspeed.startup.compat;

import com.ccr4ft3r.lightspeed.startup.compat.EarlyModPackIndexEngine.ModPackIndex;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import cpw.mods.niofs.union.UnionFileSystem;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.locating.IModFile;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Forge 1.20.1 Layer.GAME bridge delegating pack indexing and single-JAR extraction
 * to shared {@link EarlyModPackIndexEngine}.
 */
public final class PackResourcesCacheSupport {
    private static volatile Map<FileSystem, Path> singleJarModFilesystems;
    private static final Map<IModFile, Object> MOD_PACK_INDEX_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<FileSystem, Object> PATH_PACK_INDEX_CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Object UNSUPPORTED_MOD_PACK = new Object();

    private PackResourcesCacheSupport() {
    }

    public static ModPackIndex tryGetModPackIndex(IModFile modFile) {
        if (modFile == null) {
            return null;
        }
        Object cached = MOD_PACK_INDEX_CACHE.get(modFile);
        if (cached != null) {
            return cached == UNSUPPORTED_MOD_PACK ? null : (ModPackIndex) cached;
        }
        Path diskPath = resolveSingleJarArchive(modFile);
        ModPackIndex built = diskPath != null && modFile.getSecureJar().getRootPath().getFileSystem() instanceof UnionFileSystem ufs
                ? EarlyModPackIndexEngine.buildFromUnionRoot(diskPath, createEntryFilter(ufs, diskPath))
                : null;
        MOD_PACK_INDEX_CACHE.put(modFile, built != null ? built : UNSUPPORTED_MOD_PACK);
        return built;
    }

    public static ModPackIndex tryGetModPackIndex(Path rootPath) {
        if (rootPath == null || !(rootPath.getFileSystem() instanceof UnionFileSystem ufs)
                || rootPath.toAbsolutePath().normalize().getNameCount() != 0 || !isSingleSource(rootPath)) {
            return null;
        }
        Path primary = ufs.getPrimaryPath();
        if (primary == null || primary.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        Path normalized = primary.toAbsolutePath().normalize();
        Object cached = PATH_PACK_INDEX_CACHE.get(ufs);
        if (cached != null) {
            return cached == UNSUPPORTED_MOD_PACK ? null : (ModPackIndex) cached;
        }
        ModPackIndex built = Files.isRegularFile(normalized)
                ? EarlyModPackIndexEngine.buildFromUnionRoot(normalized, createEntryFilter(ufs, normalized))
                : null;
        PATH_PACK_INDEX_CACHE.putIfAbsent(ufs, built != null ? built : UNSUPPORTED_MOD_PACK);
        return built;
    }

    public static String[] splitResourcePathWithPrefix(String head0, String head1, String path) {
        return EarlyModPackIndexEngine.splitResourcePathWithPrefix(head0, head1, path);
    }

    public static String[][] tryExtractSingleJarPack(Map<PackType, Path> rootPathsByType) {
        if (rootPathsByType == null || rootPathsByType.isEmpty()) {
            return null;
        }
        for (Path root : rootPathsByType.values()) {
            if (root != null && !isSingleSource(root)) {
                return null;
            }
        }
        return EarlyModPackIndexEngine.extractSingleJarPack(
                rootPathsByType,
                getSingleJarModFilesystems(),
                ufs -> createEntryFilter(ufs, ufs.getPrimaryPath()));
    }

    public static void runGenerationTasks(List<Runnable> tasks) {
        EarlyModPackIndexEngine.runGenerationTasks(tasks);
    }

    private static Predicate<String> createEntryFilter(UnionFileSystem ufs, Path diskPath) {
        BiPredicate<String, String> filter = ufs.getFilesystemFilter();
        if (filter == null) {
            return null;
        }
        String s = diskPath.toString().replace('\\', '/');
        String basePath = s.length() > 1 && s.startsWith("/") ? s.substring(1) : s;
        return entry -> filter.test(entry, basePath);
    }

    private static boolean isCoreUnionModFile(IModFile modFile) {
        for (var info : modFile.getModInfos()) {
            String modId = info.getModId();
            if ("minecraft".equals(modId) || "forge".equals(modId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingleSource(Path path) {
        return path != null && path.getFileSystem() instanceof UnionFileSystem
                && BootstrapAgentBridge.resourceRootSourceCount(path) == 1;
    }

    public static boolean hasSingleJarModRoot(IModFile modFile) {
        return resolveSingleJarArchive(modFile) != null;
    }

    private static Path resolveSingleJarArchive(IModFile modFile) {
        if (modFile == null || modFile.getSecureJar() == null || isCoreUnionModFile(modFile)) {
            return null;
        }
        Path root = modFile.getSecureJar().getRootPath();
        Path diskPath = modFile.getFilePath();
        if (root != null
                && diskPath != null
                && root.getFileSystem() instanceof UnionFileSystem ufs
                && isSingleSource(root)
                && diskPath.getFileSystem() == FileSystems.getDefault()
                && Files.isRegularFile(diskPath)
                && diskPath.toAbsolutePath().normalize().equals(ufs.getPrimaryPath().toAbsolutePath().normalize())) {
            return diskPath;
        }
        return null;
    }

    private static Map<FileSystem, Path> getSingleJarModFilesystems() {
        Map<FileSystem, Path> cached = singleJarModFilesystems;
        if (cached != null) {
            return cached;
        }
        ModList modList = ModList.get();
        if (modList == null) {
            return Map.of();
        }
        Map<FileSystem, Path> discovered = new IdentityHashMap<>();
        for (var fileInfo : modList.getModFiles()) {
            IModFile modFile = fileInfo.getFile();
            Path diskPath = resolveSingleJarArchive(modFile);
            if (diskPath != null) {
                discovered.put(modFile.getSecureJar().getRootPath().getFileSystem(), diskPath);
            }
        }
        singleJarModFilesystems = discovered;
        return discovered;
    }
}

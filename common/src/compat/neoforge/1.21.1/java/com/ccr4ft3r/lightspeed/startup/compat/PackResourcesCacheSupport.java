package com.ccr4ft3r.lightspeed.startup.compat;

import com.ccr4ft3r.lightspeed.startup.compat.EarlyModPackIndexEngine.ModPackIndex;
import com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentBridge;
import cpw.mods.niofs.union.UnionFileSystem;
import cpw.mods.niofs.union.UnionPathFilter;
import net.minecraft.server.packs.PackType;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.locators.ModsFolderLocator;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * NeoForge 1.21.1 Layer.GAME bridge between {@link EarlyModPackIndexEngine},
 * {@code ModernFix PackResourcesCacheEngine}, and {@link net.minecraft.server.packs.PathPackResources}.
 */
public final class PackResourcesCacheSupport {
    private static volatile Map<FileSystem, Path> singleJarModFilesystems;
    private static final Map<IModFile, Object> MOD_PACK_INDEX_CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Object UNSUPPORTED_MOD_PACK = new Object();

    private PackResourcesCacheSupport() {
    }

    public static boolean sharesModFileSystem(IModFile modFile, Path root) {
        return modFile != null && modFile.getSecureJar() != null && root != null
                && root.getFileSystem() == modFile.getSecureJar().getRootPath().getFileSystem();
    }

    public static boolean isUnionFileSystem(Path path) {
        return path != null && path.getFileSystem() instanceof UnionFileSystem;
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
        ModPackIndex built = diskPath != null
                && modFile.getSecureJar().getRootPath().getFileSystem() instanceof UnionFileSystem ufs
                ? EarlyModPackIndexEngine.buildFromUnionRoot(diskPath, createEntryFilter(ufs, diskPath))
                : null;
        MOD_PACK_INDEX_CACHE.put(modFile, built != null ? built : UNSUPPORTED_MOD_PACK);
        return built;
    }

    public static ModPackIndex tryGetModPackIndex(Path rootPath) {
        if (!isUnionFileSystemRoot(rootPath)) {
            return null;
        }
        UnionFileSystem ufs = (UnionFileSystem) rootPath.getFileSystem();
        Path primary = ufs.getPrimaryPath();
        if (primary == null || primary.getFileSystem() != FileSystems.getDefault()
                || !isSingleSource(rootPath)) {
            return null;
        }
        ModList modList = ModList.get();
        if (modList != null) {
            for (var fileInfo : modList.getModFiles()) {
                IModFile modFile = fileInfo.getFile();
                if (modFile == null || modFile.getSecureJar() == null) {
                    continue;
                }
                if (modFile.getSecureJar().getRootPath().getFileSystem() == ufs) {
                    return tryGetModPackIndex(modFile);
                }
            }
        }
        if (Files.isRegularFile(primary)) {
            return EarlyModPackIndexEngine.buildFromUnionRoot(primary, createEntryFilter(ufs, primary));
        }
        return null;
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
                ufs -> createEntryFilter(ufs, ufs.getPrimaryPath())
        );
    }

    private static boolean isUnionFileSystemRoot(Path path) {
        if (path == null || !(path.getFileSystem() instanceof UnionFileSystem)) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.getNameCount() == 0;
    }

    private static boolean isSingleSource(Path path) {
        if (path == null || !(path.getFileSystem() instanceof UnionFileSystem ufs)) {
            return false;
        }
        if (Boolean.getBoolean("lightspeed.bootstrapAgent.active")) {
            // The Agent's existing getPath hook records Union's native basepaths before this query.
            ufs.getPath("/");
            int agentSources = BootstrapAgentBridge.resourceRootSourceCount(path);
            if (agentSources != BootstrapAgentBridge.UNKNOWN) {
                return agentSources == 1;
            }
        }
        return getSingleJarModFilesystems().containsKey(ufs);
    }

    private static boolean isSingleSourceModFile(IModFile modFile, Path root) {
        if (Boolean.getBoolean("lightspeed.bootstrapAgent.active")
                && root.getFileSystem() instanceof UnionFileSystem ufs) {
            ufs.getPath("/");
            int agentSources = BootstrapAgentBridge.resourceRootSourceCount(root);
            if (agentSources != BootstrapAgentBridge.UNKNOWN) {
                return agentSources == 1;
            }
        }
        ModFileDiscoveryAttributes attrs = modFile.getDiscoveryAttributes();
        if (attrs == null || attrs.parent() != null || attrs.locator() == null) {
            return false;
        }
        Class<?> locatorClass = attrs.locator().getClass();
        String locatorName = locatorClass.getName();
        if (locatorName.startsWith("net.neoforged.fml.loading.")) {
            return locatorClass == ModsFolderLocator.class
                    || "net.neoforged.fml.loading.moddiscovery.locators.MavenDirectoryLocator".equals(locatorName);
        }
        return hasJarExtension(modFile.getFilePath());
    }

    private static boolean hasJarExtension(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    public static boolean hasSingleJarModRoot(IModFile modFile) {
        return resolveSingleJarArchive(modFile) != null;
    }

    public static void clearStartupCaches() {
        synchronized (MOD_PACK_INDEX_CACHE) {
            for (Object value : MOD_PACK_INDEX_CACHE.values()) {
                if (value instanceof ModPackIndex index) {
                    index.closeArchive();
                }
            }
            MOD_PACK_INDEX_CACHE.clear();
        }
        singleJarModFilesystems = null;
    }

    private static Predicate<String> createEntryFilter(UnionFileSystem ufs, Path basePath) {
        UnionPathFilter filter = ufs == null ? null : ufs.getFilesystemFilter();
        if (filter == null) {
            return null;
        }
        return entry -> filter.test(entry, basePath);
    }

    public static void runGenerationTasks(List<Runnable> tasks) {
        EarlyModPackIndexEngine.runGenerationTasks(tasks);
    }

    private static boolean isCoreUnionModFile(IModFile modFile) {
        for (var info : modFile.getModInfos()) {
            String modId = info.getModId();
            if ("minecraft".equals(modId) || "neoforge".equals(modId) || "forge".equals(modId)) {
                return true;
            }
        }
        return false;
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
                && isSingleSourceModFile(modFile, root)
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

package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Narrows overlay-aware ZIP enumeration while retaining native validation and stream creation. */
public final class ZipResourceIndex {
    private final Path zipPath;
    private final PackPathIndex cache;
    private volatile DuplicateCheck duplicateCheck;

    public ZipResourceIndex(ZipFile zip) {
        zipPath = Path.of(zip.getName());
        cache = new PackPathIndex("zip", List.of(zipPath));
    }

    public Enumeration<? extends ZipEntry> entries(ZipFile zip, String prefix) {
        OrderedResourcePathIndex index = cache.index(PackType.CLIENT_RESOURCES, "zip", 0,
                this::scanZipEntries);
        if (index == null) {
            return null;
        }
        if (hasDuplicateNames(index)) {
            return null;
        }
        List<String> names = index.entriesStartingWith(prefix);
        if (names.size() == zip.size()) {
            return null;
        }
        List<ZipEntry> entries = new ArrayList<>(names.size());
        for (String name : names) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                cache.invalidate(PackType.CLIENT_RESOURCES, "zip", 0);
                LogUtils.getLogger().warn("Discarding resource index for {}: ZIP entry {} is missing", zip.getName(), name);
                return null;
            }
            entries.add(entry);
        }
        return Collections.enumeration(entries);
    }

    private boolean hasDuplicateNames(OrderedResourcePathIndex index) {
        DuplicateCheck check = duplicateCheck;
        if (check == null || check.index() != index) {
            Set<String> seen = new HashSet<>();
            boolean duplicates = false;
            for (String name : index.entriesStartingWith("")) {
                if (!seen.add(name)) {
                    duplicates = true;
                    break;
                }
            }
            check = new DuplicateCheck(index, duplicates);
            duplicateCheck = check;
        }
        return check.duplicates();
    }

    private record DuplicateCheck(OrderedResourcePathIndex index, boolean duplicates) { }

    public Boolean contains(String path) {
        OrderedResourcePathIndex index = cache.loadedIndex(PackType.CLIENT_RESOURCES, "zip", 0);
        return index == null ? null : index.contains(path) || index.contains(path + "/");
    }

    private List<String> scanZipEntries() {
        try (ZipFile archive = new ZipFile(zipPath.toFile())) {
            return archive.stream().map(ZipEntry::getName).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to index ZIP pack " + zipPath, exception);
        }
    }
}

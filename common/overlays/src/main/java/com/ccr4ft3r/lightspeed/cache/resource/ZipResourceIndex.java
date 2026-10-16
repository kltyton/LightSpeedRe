package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Narrows overlay-aware ZIP enumeration while retaining native validation and stream creation. */
public final class ZipResourceIndex {
    private final PackPathIndex cache;

    public ZipResourceIndex(ZipFile zip) {
        cache = new PackPathIndex("zip", List.of(Path.of(zip.getName())));
    }

    public Enumeration<? extends ZipEntry> entries(ZipFile zip, String prefix) {
        OrderedResourcePathIndex index = cache.index(PackType.CLIENT_RESOURCES, "zip", 0,
                () -> zip.stream().map(ZipEntry::getName).toList());
        if (index == null) {
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

    public Boolean contains(String path) {
        OrderedResourcePathIndex index = cache.loadedIndex(PackType.CLIENT_RESOURCES, "zip", 0);
        return index == null ? null : index.contains(path) || index.contains(path + "/");
    }
}

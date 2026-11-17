package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/** Searches in native pack order without crossing filter or custom-pack boundaries. */
public final class ResourcePackLookup {
    public interface Access<L> {
        IoSupplier<InputStream> lightspeed$lookupRead(PackResources pack, L location);
        Resource lightspeed$lookupResource(PackResources pack, L location, IoSupplier<InputStream> stream, int index);
        Boolean lightspeed$lookupContains(PackResources pack, L location);
        boolean lightspeed$lookupFiltered(ResourcePackEntry entry, L location);
    }

    public interface GroupedAccess<L> extends Access<L> {
        boolean lightspeed$canGroup(PackResources pack);
    }

    private ResourcePackLookup() {}

    public static Iterator<PackResources> filterDelegates(Iterator<PackResources> original,
            Set<PackResources> indexed, Set<PackResources> candidates) {
        return new Iterator<>() {
            private PackResources next;
            private boolean ready;

            @Override
            public boolean hasNext() {
                while (!ready && original.hasNext()) {
                    PackResources candidate = original.next();
                    if (!indexed.contains(candidate) || candidates.contains(candidate)) {
                        next = candidate;
                        ready = true;
                    }
                }
                return ready;
            }

            @Override
            public PackResources next() {
                if (!hasNext()) throw new NoSuchElementException();
                ready = false;
                return next;
            }
        };
    }

    public static <L> Optional<Resource> indexed(List<?> entries, L location, Access<L> access) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            ResourcePackEntry entry = entry(entries, index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null && !Boolean.FALSE.equals(access.lightspeed$lookupContains(pack, location))) {
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack, location);
                if (stream != null) {
                    return Optional.of(access.lightspeed$lookupResource(pack, location, stream, index));
                }
            }
            if (filtered(entry, location, access)) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Higher pack contents and filters remain live even when a lower source was cached. */
    public static <L> Optional<Resource> cached(List<?> entries, L location, int cachedIndex,
            PackResources cachedPack, Access<L> access) {
        if (cachedIndex < 0 || cachedIndex >= entries.size()
                || entry(entries, cachedIndex).lightspeed$resources() != cachedPack) {
            return direct(entries, location, access, entries.size() - 1);
        }
        for (int index = entries.size() - 1; index > cachedIndex; index--) {
            ResourcePackEntry entry = entry(entries, index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null) {
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack, location);
                if (stream != null) return Optional.of(access.lightspeed$lookupResource(pack, location, stream, index));
            }
            if (filtered(entry, location, access)) return Optional.empty();
        }
        IoSupplier<InputStream> stream = access.lightspeed$lookupRead(cachedPack, location);
        if (stream != null) {
            return Optional.of(access.lightspeed$lookupResource(cachedPack, location, stream, cachedIndex));
        }
        if (filtered(entry(entries, cachedIndex), location, access)) return Optional.empty();
        return direct(entries, location, access, cachedIndex - 1);
    }

    private static <L> Optional<Resource> direct(List<?> entries, L location, Access<L> access, int lastIndex) {
        for (int index = lastIndex; index >= 0; index--) {
            ResourcePackEntry entry = entry(entries, index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null) {
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack, location);
                if (stream != null) return Optional.of(access.lightspeed$lookupResource(pack, location, stream, index));
            }
            if (filtered(entry, location, access)) return Optional.empty();
        }
        return Optional.empty();
    }

    public static final Object MISS = new Object();

    public static <L> Optional<Resource> grouped(List<?> entries, L location, GroupedAccess<L> access) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            ResourcePackEntry entry = entry(entries, index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null && entry.lightspeed$filter() == null && access.lightspeed$canGroup(pack)) {
                Boolean present = access.lightspeed$lookupContains(pack, location);
                if (Boolean.FALSE.equals(present)) continue;
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack, location);
                if (stream != null) {
                    return Optional.of(access.lightspeed$lookupResource(pack, location, stream, index));
                }
                continue;
            }
            if (pack != null) {
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack, location);
                if (stream != null) {
                    return Optional.of(access.lightspeed$lookupResource(pack, location, stream, index));
                }
            }
            if (filtered(entry, location, access)) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static <L> boolean filtered(ResourcePackEntry entry, L location, Access<L> access) {
        if (!access.lightspeed$lookupFiltered(entry, location)) {
            return false;
        }
        LogUtils.getLogger().warn("Resource {} not found, but was filtered by pack {}", location, entry.lightspeed$name());
        return true;
    }

    private static ResourcePackEntry entry(List<?> entries, int index) {
        // The version-specific accessor binds the native entry and location types.
        return (ResourcePackEntry) entries.get(index);
    }
}

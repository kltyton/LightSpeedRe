package com.ccr4ft3r.lightspeed.cache.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private record IndexedPack(int index, PackResources pack) {}

    private ResourcePackLookup() {}

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

    public static <L> Optional<Resource> grouped(List<?> entries, L location, GroupedAccess<L> access) {
        List<IndexedPack> segment = new ArrayList<>();
        for (int index = entries.size() - 1; index >= 0; index--) {
            ResourcePackEntry entry = entry(entries, index);
            PackResources pack = entry.lightspeed$resources();
            if (pack != null && entry.lightspeed$filter() == null && access.lightspeed$canGroup(pack)) {
                segment.add(new IndexedPack(index, pack));
                continue;
            }
            Resource resource = segment(segment, location, access);
            if (resource != null) {
                return Optional.of(resource);
            }
            segment.clear();
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
        return Optional.ofNullable(segment(segment, location, access));
    }

    private static <L> Resource segment(List<IndexedPack> packs, L location, Access<L> access) {
        boolean completeIndex = true;
        for (IndexedPack pack : packs) {
            Boolean present = access.lightspeed$lookupContains(pack.pack(), location);
            if (present == null) {
                completeIndex = false;
                break;
            }
            if (present) {
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack.pack(), location);
                if (stream != null) {
                    return access.lightspeed$lookupResource(pack.pack(), location, stream, pack.index());
                }
                completeIndex = false;
                break;
            }
        }
        if (!completeIndex) {
            for (IndexedPack pack : packs) {
                IoSupplier<InputStream> stream = access.lightspeed$lookupRead(pack.pack(), location);
                if (stream != null) {
                    return access.lightspeed$lookupResource(pack.pack(), location, stream, pack.index());
                }
            }
        }
        return null;
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

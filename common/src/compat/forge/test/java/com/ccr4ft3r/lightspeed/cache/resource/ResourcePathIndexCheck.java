package com.ccr4ft3r.lightspeed.cache.resource;

import java.util.ArrayList;
import java.util.List;

public final class ResourcePathIndexCheck {
    private ResourcePathIndexCheck() {
    }

    public static void main(String[] args) {
        ResourcePathIndex index = ResourcePathIndex.from(List.of(
                "textures/block/stone.png",
                "models/item/gear.json",
                "textures/item/gear.png",
                "textures/block/stone.png",
                "textures_extra/not_a_child.png"));

        require(index.size() == 4, "paths must be sorted and deduplicated once");
        require(index.contains("models/item/gear.json"), "exact membership must hit");
        require(!index.contains("models/item/missing.json"), "exact membership must reject a miss");
        require(index.entriesUnder("textures").equals(List.of(
                "textures/block/stone.png",
                "textures/item/gear.png")), "directory lookup must not include a same-prefix sibling");
        require(index.entriesUnder("").equals(List.of(
                "models/item/gear.json",
                "textures/block/stone.png",
                "textures/item/gear.png",
                "textures_extra/not_a_child.png")), "empty directory must return the complete sorted index");
        require(ResourcePathIndex.fromSortedDistinct(index.entries()).entries().equals(index.entries()),
                "warm sorted cache must preserve the exact index without reordering");

        List<String> visited = new ArrayList<>();
        index.forEachUnder("models", visited::add);
        require(visited.equals(List.of("models/item/gear.json")), "streaming lookup must preserve sorted order");
        visited.clear();
        index.forEachAtOrUnder("models/item/gear.json", visited::add);
        require(visited.equals(List.of("models/item/gear.json")), "path-pack file lookup lost the file itself");
        visited.clear();
        index.forEachAtOrUnder("", visited::add);
        require(visited.equals(index.entries()), "root traversal emitted an entry more than once");
        OrderedResourcePathIndex ordered = OrderedResourcePathIndex.from(List.of(
                "textures/b.png", "models/a.json", "textures/a.png", "textures/b.png", "textures_extra/c.png"));
        require(ordered.entriesStartingWith("textures/").equals(List.of(
                "textures/b.png", "textures/a.png", "textures/b.png")),
                "indexed selection changed archive order or discarded duplicate entries");
        require(ordered.hasAtOrUnder("textures"), "ordered directory check must match");
        require(ordered.hasAtOrUnder("models/a.json"), "ordered exact file check must match");
        require(!ordered.hasAtOrUnder("shaders"), "ordered miss must reject absent directory");

        // Exercise > LINEAR_THRESHOLD (DirRanges map) with sibling-prefix, nested, and top-level entries.
        ResourcePathIndex large = ResourcePathIndex.from(List.of(
                "LICENSE",
                "models/block/oak.json",
                "models/item/oak.json",
                "textures/block/oak.png",
                "textures/item/oak.png",
                "textures_extra/other.png"));
        require(large.hasAtOrUnder(""), "root must match non-empty index");
        require(large.hasAtOrUnder("LICENSE"), "top-level exact file must match");
        require(!large.hasAtOrUnder("MISSING"), "absent top-level file must miss");
        require(large.hasAtOrUnder("textures"), "directory must hit");
        require(large.hasAtOrUnder("textures/block"), "nested directory must hit");
        require(large.hasAtOrUnder("textures/block/oak.png"), "exact nested file must hit");
        require(!large.hasAtOrUnder("textures/entity"), "absent nested directory must miss");
        require(!large.hasAtOrUnder("tex"), "partial prefix must not match directory");
        require(large.entriesUnder("textures").equals(List.of(
                "textures/block/oak.png", "textures/item/oak.png")),
                "large directory range must exclude sibling prefix");
        visited.clear();
        large.forEachAtOrUnder("textures/block/oak.png", visited::add);
        require(visited.equals(List.of("textures/block/oak.png")), "large exact file must emit");
        visited.clear();
        large.forEachAtOrUnder("textures/", visited::add);
        require(visited.equals(List.of("textures/block/oak.png", "textures/item/oak.png")),
                "trailing slash must enumerate descendants");
        System.out.println("RESOURCE_PATH_INDEX_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

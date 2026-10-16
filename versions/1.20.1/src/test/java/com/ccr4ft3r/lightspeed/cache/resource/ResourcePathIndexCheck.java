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
        System.out.println("RESOURCE_PATH_INDEX_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

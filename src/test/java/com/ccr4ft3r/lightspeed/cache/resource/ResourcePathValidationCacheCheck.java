package com.ccr4ft3r.lightspeed.cache.resource;

import java.util.Optional;

public final class ResourcePathValidationCacheCheck {
    private ResourcePathValidationCacheCheck() {
    }

    public static void main(String[] args) {
        ResourcePathValidationCache cache = new ResourcePathValidationCache();
        ResourcePathValidationCache second = new ResourcePathValidationCache();
        require(cache.error("textures/block").isEmpty(), "a valid resource directory was rejected");
        Optional<String> invalid = cache.error("../outside");
        require(invalid.isPresent(), "an invalid parent path was accepted");
        require(second.size() == 2, "path validation results were not shared across resource packs");
        require(cache.error("textures/block").isEmpty(), "cached valid result changed");
        require(cache.error("../outside").equals(invalid), "cached invalid diagnostic changed");
        require(cache.size() == 2, "repeated path validation was not memoized once per logical path");
        System.out.println("RESOURCE_PATH_VALIDATION_CACHE_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

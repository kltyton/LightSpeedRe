package com.ccr4ft3r.lightspeed.cache.resource;

import net.minecraft.FileUtil;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ResourcePathValidationCache {
    private static final int MAX_ENTRIES = 4_096;
    private static final ConcurrentMap<String, Optional<String>> RESULTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<LastResult> LAST_RESULT = new ThreadLocal<>();

    public Optional<String> error(String path) {
        LastResult last = LAST_RESULT.get();
        if (last != null && last.path().equals(path)) {
            return last.error();
        }
        Optional<String> existing = RESULTS.get(path);
        if (existing != null) {
            LAST_RESULT.set(new LastResult(path, existing));
            return existing;
        }
        Optional<String> validated = validate(path);
        if (RESULTS.size() >= MAX_ENTRIES) {
            LAST_RESULT.set(new LastResult(path, validated));
            return validated;
        }
        Optional<String> raced = RESULTS.putIfAbsent(path, validated);
        Optional<String> result = raced == null ? validated : raced;
        LAST_RESULT.set(new LastResult(path, result));
        return result;
    }

    int size() {
        return RESULTS.size();
    }

    private static Optional<String> validate(String path) {
        String[] error = {null};
        FileUtil.decomposePath(path).get().ifRight(result -> error[0] = result.message());
        return Optional.ofNullable(error[0]);
    }

    private record LastResult(String path, Optional<String> error) {
    }
}

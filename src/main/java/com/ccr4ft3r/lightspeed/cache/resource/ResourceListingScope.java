package com.ccr4ft3r.lightspeed.cache.resource;

public final class ResourceListingScope {
    private static final ThreadLocal<Boolean> BULK = ThreadLocal.withInitial(() -> false);

    private ResourceListingScope() { }

    public static boolean enter() {
        boolean previous = BULK.get();
        BULK.set(true);
        return previous;
    }

    public static void leave(boolean previous) {
        if (previous) BULK.set(true);
        else BULK.remove();
    }

    public static boolean bulk() { return BULK.get(); }
}

package com.ccr4ft3r.lightspeed.startup.compat;

/**
 * ASCII lookup tables preserving vanilla {@code ResourceLocation} path/namespace character rules
 * without per-character branch trees or allocations.
 */
public final class ResourceLocationLookupSupport {
    public static final boolean[] PATH_CHARS = new boolean[128];
    public static final boolean[] NAMESPACE_CHARS = new boolean[128];

    static {
        NAMESPACE_CHARS['_'] = true;
        NAMESPACE_CHARS['-'] = true;
        NAMESPACE_CHARS['.'] = true;
        for (int c = 'a'; c <= 'z'; c++) {
            NAMESPACE_CHARS[c] = true;
        }
        for (int c = '0'; c <= '9'; c++) {
            NAMESPACE_CHARS[c] = true;
        }
        System.arraycopy(NAMESPACE_CHARS, 0, PATH_CHARS, 0, 128);
        PATH_CHARS['/'] = true;
    }

    private ResourceLocationLookupSupport() {
    }
}

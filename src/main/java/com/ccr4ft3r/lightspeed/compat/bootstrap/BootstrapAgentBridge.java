package com.ccr4ft3r.lightspeed.compat.bootstrap;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BootstrapAgentBridge {
    public static final int UNKNOWN = -1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final boolean RESOURCE_INDEX_ENABLED = Boolean.getBoolean("lightspeed.bootstrapAgent.resourceIndex");
    private static final Method RESOURCE_ENTRIES;
    private static final Method CONTAINS_RESOURCE;
    private static final Method RESOURCE_NAMESPACES;
    private static final Method RESOURCE_BYTES;
    private static final Method RECORD_RESOURCE_BYTES;
    private static final Method PERSIST_RESOURCE_IMAGE;

    static {
        Method resourceEntries = null;
        Method containsResource = null;
        Method resourceNamespaces = null;
        Method resourceBytes = null;
        Method recordResourceBytes = null;
        Method persistResourceImage = null;
        if (Boolean.getBoolean("lightspeed.bootstrapAgent.active")) {
            try {
                Class<?> hooks = Class.forName(
                        "com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks", false, null);
                resourceEntries = hooks.getMethod("resourceEntries", Path.class, String.class, String.class);
                containsResource = hooks.getMethod("containsResource", Path.class, String.class);
                resourceNamespaces = hooks.getMethod("resourceNamespaces", Path.class, String.class);
                resourceBytes = hooks.getMethod("resourceBytes", Path.class, String.class);
                recordResourceBytes = hooks.getMethod("recordResourceBytes", Path.class, String.class, byte[].class);
                persistResourceImage = hooks.getMethod("persistResourceImage");
            } catch (ReflectiveOperationException | LinkageError exception) {
                logFailure("initialize", exception);
            }
        }
        RESOURCE_ENTRIES = resourceEntries;
        CONTAINS_RESOURCE = containsResource;
        RESOURCE_NAMESPACES = resourceNamespaces;
        RESOURCE_BYTES = resourceBytes;
        RECORD_RESOURCE_BYTES = recordResourceBytes;
        PERSIST_RESOURCE_IMAGE = persistResourceImage;
    }

    private BootstrapAgentBridge() {
    }

    public static boolean isAvailable() {
        return RESOURCE_INDEX_ENABLED && RESOURCE_ENTRIES != null;
    }

    public static String[] resourceEntries(Path path, String basePrefix, String requestedPath) {
        if (!RESOURCE_INDEX_ENABLED) {
            return null;
        }
        Object result = invoke(RESOURCE_ENTRIES, "list resources", path, basePrefix, requestedPath);
        return result instanceof String[] entries ? entries : null;
    }

    public static int containsResource(Path path, String name) {
        if (!RESOURCE_INDEX_ENABLED) {
            return UNKNOWN;
        }
        Object result = invoke(CONTAINS_RESOURCE, "test resource membership", path, name);
        return result instanceof Integer value ? value : UNKNOWN;
    }

    public static String[] resourceNamespaces(Path path, String directory) {
        if (!RESOURCE_INDEX_ENABLED) {
            return null;
        }
        Object result = invoke(RESOURCE_NAMESPACES, "list namespaces", path, directory);
        return result instanceof String[] namespaces ? namespaces : null;
    }

    public static byte[] resourceBytes(Path path, String name) {
        if (!RESOURCE_INDEX_ENABLED) {
            return null;
        }
        Object result = invoke(RESOURCE_BYTES, "load startup resource image", path, name);
        return result instanceof byte[] bytes ? bytes : null;
    }

    public static void recordResourceBytes(Path path, String name, byte[] bytes) {
        if (RESOURCE_INDEX_ENABLED) {
            invoke(RECORD_RESOURCE_BYTES, "record startup resource image", path, name, bytes);
        }
    }

    public static void persistResourceImage() {
        if (RESOURCE_INDEX_ENABLED) {
            invoke(PERSIST_RESOURCE_IMAGE, "persist startup resource image");
        }
    }

    private static Object invoke(Method method, String operation, Object... arguments) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(null, arguments);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            logFailure(operation, exception);
            return null;
        }
    }

    private static void logFailure(String operation, Throwable throwable) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("Lightspeed bootstrap bridge could not {}; falling back to the standard startup path", operation, throwable);
        }
    }
}

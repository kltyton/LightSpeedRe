package com.ccr4ft3r.lightspeed.compat.bootstrap;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Consumer;

public final class BootstrapAgentBridge {
    public static final int UNKNOWN = -1;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final boolean RESOURCE_INDEX_ENABLED = Boolean.getBoolean("lightspeed.bootstrapAgent.resourceIndex");
    private static volatile Access access;

    static {
        refresh();
    }

    public static void refresh() {
        if (access == null && Boolean.getBoolean("lightspeed.bootstrapAgent.active")) {
            try {
                Constructor<?> constructor = Class.forName(
                        "com.ccr4ft3r.lightspeed.compat.bootstrap.BootstrapAgentAccess", true,
                        BootstrapAgentBridge.class.getClassLoader()).getDeclaredConstructor();
                constructor.setAccessible(true);
                access = (Access) constructor.newInstance();
            } catch (ReflectiveOperationException | LinkageError exception) {
                logFailure("initialize", exception);
            }
        }
    }

    private BootstrapAgentBridge() {
    }

    public static boolean isAvailable() {
        return RESOURCE_INDEX_ENABLED && access != null;
    }

    public static void withRegistryFilter(Predicate<?> filter, Object key, Runnable action) {
        Access current = access;
        if (current == null) action.run();
        else current.withRegistryFilter(filter, key, action);
    }

    public static void withRegisterEvent(Object event, Object key, Runnable action) {
        Access current = access;
        if (current == null) action.run();
        else current.withRegisterEvent(event, key, action);
    }

    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> keyedRegisterConsumer(Object key, Consumer<T> action) {
        Access current = access;
        return current == null ? action : (Consumer<T>) current.keyedRegisterConsumer(key, action);
    }

    public static int bindResourceIndex(Path path) {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return UNKNOWN;
        }
        try {
            return current.bindResourceIndex(path);
        } catch (RuntimeException | LinkageError exception) {
            disable("bind resource index", exception);
            return UNKNOWN;
        }
    }

    public static List<String> resourceEntries(int handle, String basePrefix, String requestedPath) {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return null;
        }
        try {
            return current.resourceEntries(handle, basePrefix, requestedPath);
        } catch (RuntimeException | LinkageError exception) {
            disable("list resources", exception);
            return null;
        }
    }

    public static int containsResource(int handle, String name) {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return UNKNOWN;
        }
        try {
            return current.containsResource(handle, name);
        } catch (RuntimeException | LinkageError exception) {
            disable("test resource membership", exception);
            return UNKNOWN;
        }
    }

    public static Set<String> resourceNamespaces(int handle, String directory) {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return null;
        }
        try {
            return current.resourceNamespaces(handle, directory);
        } catch (RuntimeException | LinkageError exception) {
            disable("list namespaces", exception);
            return null;
        }
    }

    public static byte[] resourceBytes(int handle, String name) {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return null;
        }
        try {
            return current.resourceBytes(handle, name);
        } catch (RuntimeException | LinkageError exception) {
            disable("load startup image", exception);
            return null;
        }
    }

    public static void recordResourceBytes(int handle, String name, byte[] bytes) {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return;
        }
        try {
            current.recordResourceBytes(handle, name, bytes);
        } catch (RuntimeException | LinkageError exception) {
            disable("record startup image", exception);
        }
    }

    public static void persistResourceImage() {
        Access current = access;
        if (!RESOURCE_INDEX_ENABLED || current == null) {
            return;
        }
        try {
            current.persistResourceImage();
        } catch (RuntimeException | LinkageError exception) {
            disable("persist startup image", exception);
        }
    }

    public static void finishStartupHttpWindow() {
        Access current = access;
        if (current != null) current.finishStartupHttpWindow();
    }


    private static void disable(String operation, Throwable throwable) {
        access = null;
        logFailure(operation, throwable);
    }

    private static void logFailure(String operation, Throwable throwable) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("Lightspeed bootstrap bridge could not {}; falling back to the Mod-side resource index",
                    operation, throwable);
        }
    }

    interface Access {
        void withRegistryFilter(Predicate<?> filter, Object key, Runnable action);

        void withRegisterEvent(Object event, Object key, Runnable action);

        Consumer<?> keyedRegisterConsumer(Object key, Consumer<?> action);

        int bindResourceIndex(Path path);

        List<String> resourceEntries(int handle, String basePrefix, String requestedPath);

        int containsResource(int handle, String name);

        Set<String> resourceNamespaces(int handle, String directory);

        byte[] resourceBytes(int handle, String name);

        void recordResourceBytes(int handle, String name, byte[] bytes);

        void persistResourceImage();

        void finishStartupHttpWindow();


    }
}

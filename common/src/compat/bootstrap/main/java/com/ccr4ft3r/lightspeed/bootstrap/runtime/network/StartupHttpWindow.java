package com.ccr4ft3r.lightspeed.bootstrap.runtime.network;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/** Bounds JDK HTTP connection waits while the startup lifecycle is running. */
public final class StartupHttpWindow {
    private static final int STARTUP_TIMEOUT_MS = 1_000;
    private static final Duration HTTP_CLIENT_LIMIT = Duration.ofMillis(STARTUP_TIMEOUT_MS);
    private static final LongAdder HTTP_CLIENT_BOUNDS = new LongAdder();
    private static VarHandle readTimeout;
    private static VarHandle connectTimeout;
    private static int previousRead;
    private static int previousConnect;
    private static volatile boolean active;
    private static boolean started;
    private static boolean restored;

    private StartupHttpWindow() { }

    public static synchronized void start() {
        if (Runtime.version().feature() != 17 && Runtime.version().feature() != 21
                && Runtime.version().feature() != 25) return;
        try {
            Class<?> client = Class.forName("sun.net.NetworkClient", true, null);
            RuntimeModuleAccess.openToAgent(client);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(client, MethodHandles.lookup());
            readTimeout = lookup.findStaticVarHandle(client, "defaultSoTimeout", int.class);
            connectTimeout = lookup.findStaticVarHandle(client, "defaultConnectTimeout", int.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot set startup HTTP time limits", exception);
        }
        previousRead = (int) readTimeout.getVolatile();
        previousConnect = (int) connectTimeout.getVolatile();
        if (previousRead <= 0) readTimeout.setVolatile(STARTUP_TIMEOUT_MS);
        if (previousConnect <= 0) connectTimeout.setVolatile(STARTUP_TIMEOUT_MS);
        active = true;
        started = true;
    }

    public static synchronized void finish() {
        if (!active) return;
        readTimeout.setVolatile(previousRead);
        connectTimeout.setVolatile(previousConnect);
        active = false;
        restored = true;
    }

    public static boolean started() { return started; }
    public static boolean restored() { return restored; }

    public static Optional<Duration> limitHttpClientConnect(Optional<Duration> configured) {
        if (!active || configured.isPresent() && configured.get().compareTo(HTTP_CLIENT_LIMIT) <= 0) {
            return configured;
        }
        HTTP_CLIENT_BOUNDS.increment();
        return Optional.of(HTTP_CLIENT_LIMIT);
    }

    public static long httpClientBounds() { return HTTP_CLIENT_BOUNDS.sum(); }
}

package com.ccr4ft3r.lightspeed.compat.bootstrap;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Consumer;

final class BootstrapAgentAccess implements BootstrapAgentBridge.Access {
    @Override
    public void withRegistryFilter(Predicate<?> filter, Object key, Runnable action) {
        BootstrapHooks.withRegistryFilter(filter, key, action);
    }

    @Override
    public void withRegisterEvent(Object event, Object key, Runnable action) {
        BootstrapHooks.withRegisterEvent(event, key, action);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Consumer<?> keyedRegisterConsumer(Object key, Consumer<?> action) {
        return BootstrapHooks.keyedRegisterConsumer(key, (Consumer) action);
    }

    @Override
    public int bindResourceIndex(Path path) {
        return BootstrapHooks.bindResourceIndex(path);
    }

    @Override
    public List<String> resourceEntries(int handle, String basePrefix, String requestedPath) {
        return BootstrapHooks.resourceEntries(handle, basePrefix, requestedPath);
    }

    @Override
    public int containsResource(int handle, String name) {
        return BootstrapHooks.containsResource(handle, name);
    }

    @Override
    public Set<String> resourceNamespaces(int handle, String directory) {
        return BootstrapHooks.resourceNamespaces(handle, directory);
    }

    @Override
    public byte[] resourceBytes(int handle, String name) {
        return BootstrapHooks.resourceBytes(handle, name);
    }

    @Override
    public void recordResourceBytes(int handle, String name, byte[] bytes) {
        BootstrapHooks.recordResourceBytes(handle, name, bytes);
    }

    @Override
    public void persistResourceImage() {
        BootstrapHooks.persistResourceImage();
    }

    @Override
    public void finishStartupHttpWindow() {
        BootstrapHooks.finishStartupHttpWindow();
    }


}

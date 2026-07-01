package com.ccr4ft3r.lightspeed.compat;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public final class ResourceReloadFailureGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ResourceReloadFailureGuard() {
    }

    public static List<PreparableReloadListener> wrap(List<PreparableReloadListener> listeners) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures) {
            return listeners;
        }
        return listeners.stream()
                .map(listener -> shouldWrap(listener) ? new GuardedReloadListener(listener) : listener)
                .toList();
    }

    private static boolean shouldWrap(PreparableReloadListener listener) {
        String className = listener.getClass().getName();
        return !isCoreClass(className) && matchesConfiguredPattern(className);
    }

    private static boolean shouldIsolate(PreparableReloadListener listener, Throwable throwable) {
        return GlobalCache.shouldIsolateModdedResourceReloadFailures
                && !isFatal(throwable)
                && shouldWrap(listener);
    }

    private static boolean matchesConfiguredPattern(String className) {
        for (String pattern : GlobalCache.isolatedResourceReloadListenerPatterns) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith(".*") && className.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
            if (className.equals(pattern) || className.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCoreClass(String className) {
        return className.startsWith("net.minecraft.")
                || className.startsWith("net.neoforged.")
                || className.startsWith("net.minecraftforge.")
                || className.startsWith("com.mojang.")
                || className.startsWith("java.")
                || className.startsWith("javax.");
    }

    private static boolean isFatal(Throwable throwable) {
        return throwable instanceof VirtualMachineError || throwable.getClass().getName().equals("java.lang.ThreadDeath");
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record GuardedReloadListener(PreparableReloadListener delegate) implements PreparableReloadListener {
        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                              ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler,
                                              Executor backgroundExecutor, Executor gameExecutor) {
            CompletableFuture<Void> future;
            try {
                future = delegate.reload(barrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            } catch (Throwable throwable) {
                return handleFailure(throwable);
            }
            return future.handle((ignored, throwable) -> {
                if (throwable == null) {
                    return null;
                }
                Throwable cause = unwrap(throwable);
                if (shouldIsolate(delegate, cause)) {
                    logIsolated(cause);
                    return null;
                }
                throw new CompletionException(cause);
            });
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        private CompletableFuture<Void> handleFailure(Throwable throwable) {
            Throwable cause = unwrap(throwable);
            if (shouldIsolate(delegate, cause)) {
                logIsolated(cause);
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(cause);
        }

        private void logIsolated(Throwable throwable) {
            LOGGER.error("Lightspeed isolated resource reload failure in {} ({})",
                    delegate.getName(), delegate.getClass().getName(), throwable);
        }
    }
}

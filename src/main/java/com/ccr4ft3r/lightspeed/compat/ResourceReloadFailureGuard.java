package com.ccr4ft3r.lightspeed.compat;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public final class ResourceReloadFailureGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ResourceReloadFailureGuard() {
    }

    public static <T> CompletableFuture<T> guard(
            PreparableReloadListener listener,
            Supplier<CompletableFuture<T>> reload) {
        CompletableFuture<T> future;
        try {
            future = reload.get();
        } catch (RuntimeException | LinkageError throwable) {
            return handleFailure(listener, throwable);
        }
        if (!shouldWrap(listener)) {
            return future;
        }
        return future.handle((result, throwable) -> {
            if (throwable == null) {
                return result;
            }
            Throwable cause = unwrap(throwable);
            if (shouldIsolate(listener, cause)) {
                logIsolated(listener, cause);
                return null;
            }
            throw new CompletionException(cause);
        });
    }

    public static boolean shouldIsolate(Object owner) {
        return GlobalCache.shouldIsolateModdedResourceReloadFailures
                && owner != null
                && shouldWrapClass(owner.getClass().getName());
    }

    public static boolean shouldIsolateRendererFailure(String rendererNamespace, Object owner, Throwable throwable) {
        if (!GlobalCache.shouldIsolateModdedResourceReloadFailures || throwable == null || containsFatal(throwable)) {
            return false;
        }
        if (owner != null && shouldWrapClass(owner.getClass().getName())) {
            return true;
        }
        if (rendererNamespace != null
                && !"minecraft".equals(rendererNamespace)
                && GlobalCache.isolatedResourceReloadListenerPatterns.contains("*")) {
            return true;
        }
        return hasConfiguredNonCoreStackFrame(throwable);
    }

    private static boolean shouldWrap(PreparableReloadListener listener) {
        return shouldWrapClass(listener.getClass().getName());
    }

    private static boolean shouldWrapClass(String className) {
        return !isCoreClass(className) && matchesConfiguredPattern(className);
    }

    private static boolean shouldIsolate(PreparableReloadListener listener, Throwable throwable) {
        return GlobalCache.shouldIsolateModdedResourceReloadFailures
                && !isFatal(throwable)
                && shouldWrap(listener)
                && isOwnedBy(listener, throwable);
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
                || className.startsWith("com.ccr4ft3r.lightspeed.")
                || className.startsWith("com.google.common.")
                || className.startsWith("com.llamalad7.mixinextras.")
                || className.startsWith("org.apache.commons.")
                || className.startsWith("org.apache.logging.")
                || className.startsWith("org.objectweb.asm.")
                || className.startsWith("org.slf4j.")
                || className.startsWith("org.spongepowered.asm.")
                || className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }

    private static boolean isFatal(Throwable throwable) {
        return throwable instanceof VirtualMachineError || throwable.getClass().getName().equals("java.lang.ThreadDeath");
    }

    private static boolean containsFatal(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(throwable);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (isFatal(current)) {
                return true;
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean hasConfiguredNonCoreStackFrame(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(throwable);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if (isLightspeedBoundary(frame)) {
                    break;
                }
                if (shouldWrapClass(frame.getClassName())) {
                    return true;
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean isOwnedBy(PreparableReloadListener listener, Throwable throwable) {
        String ownerClass = listener.getClass().getName();
        String ownerPackage = listener.getClass().getPackageName();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(throwable);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                String frameClass = frame.getClassName();
                if (frameClass.equals(ownerClass)
                        || frameClass.startsWith(ownerClass + "$")
                        || (!ownerPackage.isEmpty() && frameClass.startsWith(ownerPackage + "."))) {
                    return true;
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean isLightspeedBoundary(StackTraceElement frame) {
        return frame.getClassName().startsWith("com.ccr4ft3r.lightspeed.")
                || frame.getMethodName().contains("lightspeed$");
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletableFuture<T> handleFailure(
            PreparableReloadListener listener,
            Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (shouldIsolate(listener, cause)) {
            logIsolated(listener, cause);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(cause);
    }

    private static void logIsolated(PreparableReloadListener listener, Throwable throwable) {
        LOGGER.error("Lightspeed isolated resource reload failure in {} ({})",
                listener.getName(), listener.getClass().getName(), throwable);
    }
}

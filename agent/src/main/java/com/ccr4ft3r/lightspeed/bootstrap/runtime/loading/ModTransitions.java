package com.ccr4ft3r.lightspeed.bootstrap.runtime.loading;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

public final class ModTransitions {
    private ModTransitions() { }

    public static CompletableFuture<Void> run(Runnable action, Executor executor,
            BiConsumer<? super Void, ? super Throwable> completion, String modId, String phase) {
        TransitionEvent candidate = new TransitionEvent();
        TransitionEvent event = candidate.isEnabled() ? candidate : null;
        if (event != null) {
            event.modId = modId;
            event.phase = phase;
        }
        CompletableFuture<Void> source = new CompletableFuture<>();
        CompletableFuture<Void> result = source.whenComplete((value, failure) -> {
            if (event != null) {
                event.end();
                event.failed = failure != null;
                event.commit();
            }
            completion.accept(value, failure);
        });
        // Install the completion before submitting: even an immediately completed
        // task must clear ModLoadingContext on its worker rather than the caller.
        source.completeAsync(() -> {
            if (event != null) event.begin();
            action.run();
            return null;
        }, executor);
        return result;
    }

    @Name("com.ccr4ft3r.lightspeed.ModTransition")
    @Label("Mod loading transition")
    @StackTrace(false)
    @Threshold("10 ms")
    private static final class TransitionEvent extends Event {
        String modId;
        String phase;
        boolean failed;
    }
}

package com.ccr4ft3r.lightspeed.client.renderer;

import com.google.common.collect.ImmutableMap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Intercepts single-entry put calls from replayed renderer registration lambdas without allocating a full map. */
public final class CapturingMapBuilder<K, V> extends ImmutableMap.Builder<K, V> {
    private final Deque<Slot<V>> captureStack = new ArrayDeque<>();
    private final RendererLoadingContext.Snapshot baselineContext = RendererLoadingContext.capture();
    private Map<K, V> deferredMap;

    @Override
    public ImmutableMap.Builder<K, V> put(K key, V value) {
        synchronized (captureStack) {
            Slot<V> top = captureStack.peek();
            if (top != null) {
                top.value = value;
                top.assigned = true;
                return this;
            }
        }
        return super.put(key, value);
    }

    public void attachDeferredMap(Map<K, V> map) {
        this.deferredMap = map;
    }

    public Map<K, V> deferredMap() {
        return deferredMap;
    }

    public RendererLoadingContext.Snapshot baselineContext() {
        return baselineContext;
    }

    V captureSingle(Runnable producer) {
        synchronized (captureStack) {
            Slot<V> slot = new Slot<>();
            captureStack.push(slot);
            try {
                RendererLoadingContext.runPreserving(baselineContext, producer);
                return slot.assigned ? slot.value : null;
            } finally {
                captureStack.pop();
            }
        }
    }

    private static final class Slot<V> {
        V value;
        boolean assigned;
    }
}

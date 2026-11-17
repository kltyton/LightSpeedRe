package com.ccr4ft3r.lightspeed.client.renderer;

import com.google.common.collect.ImmutableMap;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Lazily constructs real renderers on first access while running the original registration lambda intact. */
public final class DeferredRendererMap<K, P, V> extends AbstractMap<K, V> {
    private static final Object NULL_RENDERER = new Object();

    private final Map<K, P> providers;
    private final BiConsumer<K, P> registrationAction;
    private final CapturingMapBuilder<K, V> capturingBuilder;
    private final ConcurrentHashMap<K, Object> resolved = new ConcurrentHashMap<>();

    public DeferredRendererMap(Map<K, P> providers, BiConsumer<K, P> registrationAction,
                               CapturingMapBuilder<K, V> capturingBuilder) {
        this.providers = providers;
        this.registrationAction = registrationAction;
        this.capturingBuilder = capturingBuilder;
    }

    public static <K, P, V> boolean tryDefer(Map<K, P> providers, BiConsumer<K, P> action,
                                             ImmutableMap.Builder<K, V> builder) {
        if (builder instanceof CapturingMapBuilder<K, V> capturing) {
            capturing.attachDeferredMap(new DeferredRendererMap<>(providers, action, capturing));
            return true;
        }
        return false;
    }

    public static <K, V> Map<K, V> resolveDeferred(ImmutableMap.Builder<K, V> builder) {
        return builder instanceof CapturingMapBuilder<K, V> capturing ? capturing.deferredMap() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        Object cached = resolved.get(key);
        if (cached != null) {
            return cached == NULL_RENDERER ? null : (V) cached;
        }
        P provider = providers.get(key);
        if (provider == null) {
            return null;
        }
        return resolve((K) key, provider);
    }

    @Override
    public boolean containsKey(Object key) {
        return providers.containsKey(key);
    }

    @Override
    public int size() {
        return providers.size();
    }

    @Override
    public boolean isEmpty() {
        return providers.isEmpty();
    }

    @Override
    public Set<K> keySet() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<K, V>> iterator() {
                Iterator<K> keys = providers.keySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return keys.hasNext();
                    }

                    @Override
                    public Entry<K, V> next() {
                        K key = keys.next();
                        return new SimpleImmutableEntry<>(key, get(key));
                    }
                };
            }

            @Override
            public int size() {
                return providers.size();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private V resolve(K key, P provider) {
        synchronized (capturingBuilder) {
            Object existing = resolved.get(key);
            if (existing != null) {
                return existing == NULL_RENDERER ? null : (V) existing;
            }
            V created = capturingBuilder.captureSingle(() -> registrationAction.accept(key, provider));
            Object stored = created == null ? NULL_RENDERER : created;
            resolved.put(key, stored);
            return created;
        }
    }
}

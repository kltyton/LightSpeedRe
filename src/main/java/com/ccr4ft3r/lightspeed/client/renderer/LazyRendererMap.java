package com.ccr4ft3r.lightspeed.client.renderer;

import net.minecraft.client.renderer.entity.EntityRenderer;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class LazyRendererMap<K, V extends EntityRenderer<?>> extends AbstractMap<K, V> {
    private final Map<K, V> delegate;

    public LazyRendererMap(Map<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override
    public V get(Object key) {
        return resolve(delegate.get(key));
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> entries = new LinkedHashSet<>(delegate.size());
        delegate.forEach((key, renderer) -> entries.add(new SimpleImmutableEntry<>(key, resolve(renderer))));
        return Set.copyOf(entries);
    }

    @SuppressWarnings("unchecked")
    private V resolve(V renderer) {
        return renderer instanceof LazyEntityRenderer<?> lazy ? (V) lazy.resolveDelegate() : renderer;
    }
}

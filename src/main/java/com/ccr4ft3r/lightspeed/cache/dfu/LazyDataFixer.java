package com.ccr4ft3r.lightspeed.cache.dfu;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;

import java.util.Objects;
import java.util.function.Supplier;

public final class LazyDataFixer implements DataFixer {
    private Supplier<DataFixer> supplier;
    private volatile DataFixer delegate;

    public LazyDataFixer(Supplier<DataFixer> supplier) {
        this.supplier = Objects.requireNonNull(supplier);
    }

    @Override
    public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion) {
        return delegate().update(type, input, version, newVersion);
    }

    @Override
    public Schema getSchema(int key) {
        return delegate().getSchema(key);
    }

    private DataFixer delegate() {
        DataFixer current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = delegate;
            if (current == null) {
                current = Objects.requireNonNull(supplier.get());
                delegate = current;
                supplier = null;
            }
            return current;
        }
    }
}

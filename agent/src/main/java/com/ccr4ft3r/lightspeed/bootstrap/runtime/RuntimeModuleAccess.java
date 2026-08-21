package com.ccr4ft3r.lightspeed.bootstrap.runtime;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;

public final class RuntimeModuleAccess {
    private static volatile Instrumentation instrumentation;

    private RuntimeModuleAccess() {
    }

    public static void install(Instrumentation value) {
        instrumentation = value;
    }

    public static void openToAgent(Class<?> type) {
        Instrumentation current = instrumentation;
        Module source = type.getModule();
        Module target = RuntimeModuleAccess.class.getModule();
        String packageName = type.getPackageName();
        if (current == null || !source.isNamed() || source.isOpen(packageName, target)) {
            return;
        }
        current.redefineModule(source, Set.of(), Map.of(), Map.of(packageName, Set.of(target)), Set.of(), Map.of());
    }
}

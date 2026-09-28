package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import org.objectweb.asm.ClassReader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Superclasses observed after ModLauncher has finished transforming their bytes. */
public final class ClassHierarchy {
    private static final String EVENT = "net/minecraftforge/eventbus/api/Event";
    private static final Map<ClassLoader, Map<String, Boolean>> NON_EVENTS = new ConcurrentHashMap<>();

    private ClassHierarchy() { }

    public static void observe(ClassLoader loader, String name, byte[] bytes) {
        if (loader == null || name == null) return;
        String loaderType = loader.getClass().getName();
        if (!loaderType.equals("cpw.mods.modlauncher.TransformingClassLoader")
                && !loaderType.equals("cpw.mods.cl.ModuleClassLoader")) return;
        String parent = new ClassReader(bytes).getSuperName();
        Map<String, Boolean> known = NON_EVENTS.computeIfAbsent(loader, ignored -> new ConcurrentHashMap<>());
        Boolean nonEvent = name.equals(EVENT) ? Boolean.FALSE
                : parent == null ? null : parent.startsWith("java/") ? Boolean.TRUE : known.get(parent);
        if (nonEvent != null) known.put(name, nonEvent);
        else known.remove(name);
    }

    public static boolean excludesEvent(ClassLoader loader, String parent) {
        if (parent == null || parent.equals(EVENT)) return false;
        if (parent.startsWith("java/")) return true;
        Map<String, Boolean> known = NON_EVENTS.get(loader);
        return known != null && Boolean.TRUE.equals(known.get(parent));
    }

}

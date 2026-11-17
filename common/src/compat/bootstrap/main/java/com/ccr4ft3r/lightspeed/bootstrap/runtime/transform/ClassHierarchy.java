package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Event ancestry of classes successfully defined by the module loader. */
public final class ClassHierarchy {
    private static final Set<String> EVENTS = Set.of(
            "net/minecraftforge/eventbus/api/Event",
            "net/neoforged/bus/api/Event");
    private static final Map<ClassLoader, Map<String, Boolean>> NON_EVENTS = new ConcurrentHashMap<>();

    private ClassHierarchy() { }

    public static void observeDefined(Class<?> type) {
        if (type.isArray()) return;
        ClassLoader loader = type.getClassLoader();
        if (loader == null) return;
        String loaderType = loader.getClass().getName();
        if (!loaderType.equals("cpw.mods.modlauncher.TransformingClassLoader")
                && !loaderType.equals("cpw.mods.cl.ModuleClassLoader")) return;
        Map<String, Boolean> known = NON_EVENTS.computeIfAbsent(loader, ignored -> new ConcurrentHashMap<>());
        String name = type.getName().replace('.', '/');
        boolean nonEvent = true;
        for (Class<?> ancestor = type; ancestor != null; ancestor = ancestor.getSuperclass()) {
            if (EVENTS.contains(ancestor.getName().replace('.', '/'))) {
                nonEvent = false;
                break;
            }
        }
        known.put(name, nonEvent);
    }

    public static boolean excludesEvent(ClassLoader loader, String parent) {
        if (parent == null || EVENTS.contains(parent)) return false;
        if (parent.startsWith("java/")) return true;
        Map<String, Boolean> known = NON_EVENTS.get(loader);
        return known != null && Boolean.TRUE.equals(known.get(parent));
    }

}

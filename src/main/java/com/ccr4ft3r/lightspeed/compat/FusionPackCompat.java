package com.ccr4ft3r.lightspeed.compat;

import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FusionPackCompat {
    private static final String[] OVERRIDE_FIELD_SUFFIXES = {
            "overridesFolderName",
            "overridesFolderRoot",
            "overridesFolder"
    };
    private static final ConcurrentMap<Class<?>, List<Field>> OVERRIDE_FIELDS_BY_CLASS = new ConcurrentHashMap<>();

    private FusionPackCompat() {
    }

    public static boolean hasOverrides(Object packResources) {
        if (packResources == null || !isFusionLoaded()) {
            return false;
        }

        for (Field field : OVERRIDE_FIELDS_BY_CLASS.computeIfAbsent(packResources.getClass(), FusionPackCompat::findOverrideFields)) {
            try {
                if (field.get(packResources) != null) {
                    return true;
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Fusion is optional; if reflection is blocked, keep the normal Lightspeed path.
            }
        }
        return false;
    }

    private static boolean isFusionLoaded() {
        try {
            return ModList.get().isLoaded("fusion");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<Field> findOverrideFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!isFusionOverrideField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException ignored) {
                    // Leave inaccessible fields out of the compatibility check.
                }
            }
        }
        return List.copyOf(fields);
    }

    private static boolean isFusionOverrideField(String fieldName) {
        for (String suffix : OVERRIDE_FIELD_SUFFIXES) {
            if (fieldName.equals(suffix) || fieldName.endsWith("$" + suffix) || fieldName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}

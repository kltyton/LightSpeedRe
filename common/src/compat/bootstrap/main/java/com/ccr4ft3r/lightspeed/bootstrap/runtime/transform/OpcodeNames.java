package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import java.lang.reflect.Field;

public final class OpcodeNames {
    private static final ClassValue<String[]> NAMES = new ClassValue<>() {
        @Override
        protected String[] computeValue(Class<?> constants) {
            String[] names = new String[256];
            for (int opcode = 0; opcode < names.length; opcode++) {
                names[opcode] = Integer.toString(opcode);
            }
            boolean started = false;
            boolean[] assigned = new boolean[names.length];
            try {
                for (Field field : constants.getDeclaredFields()) {
                    started |= field.getName().equals("UNINITIALIZED_THIS");
                    if (started && field.getType() == int.class) {
                        int opcode = field.getInt(null);
                        if (opcode > 0 && opcode < names.length && !assigned[opcode]) {
                            names[opcode] = field.getName();
                            assigned[opcode] = true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Mixin stops at the first reflection failure and uses the numeric fallback.
            }
            return names;
        }
    };

    private OpcodeNames() {
    }

    public static String get(Class<?> constants, int opcode) {
        return NAMES.get(constants)[opcode];
    }
}

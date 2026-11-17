package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Checks native annotation effects without reading method code. */
final class AnnotationEffects {
    private static final String ONLY_IN = "Lnet/minecraftforge/api/distmarker/OnlyIn;";
    private static final String ONLY_INS = "Lnet/minecraftforge/api/distmarker/OnlyIns;";
    private static final String OBJECT_HOLDER = "Lnet/minecraftforge/registries/ObjectHolder;";
    private static final String SUBSCRIBE_EVENT = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final byte[][] DESCRIPTORS = {
            ONLY_IN.getBytes(StandardCharsets.US_ASCII), ONLY_INS.getBytes(StandardCharsets.US_ASCII),
            OBJECT_HOLDER.getBytes(StandardCharsets.US_ASCII), SUBSCRIBE_EVENT.getBytes(StandardCharsets.US_ASCII)
    };
    private static final int HOLDER_FLAGS = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
    private static final RewriteRequired REWRITE_REQUIRED = new RewriteRequired();
    private static final ClassValue<VarHandle> DIST = new ClassValue<>() {
        @Override
        protected VarHandle computeValue(Class<?> type) {
            try {
                // The service exposes a setter, but no getter for the state its predicate reads.
                RuntimeModuleAccess.openToAgent(type);
                return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findStaticVarHandle(type, "DIST", String.class);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unsupported native dist predicate", exception);
            }
        }
    };

    private AnnotationEffects() { }

    static boolean requiresRewrite(ClassReader reader, byte[] bytes, int consumers, Class<?> distCleaner) {
        int seen = 0;
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0 || reader.readByte(offset - 1) != 1) continue;
            int length = reader.readUnsignedShort(offset);
            for (int kind = 0; kind < DESCRIPTORS.length; kind++) {
                if ((consumers & (1 << kind)) == 0 || DESCRIPTORS[kind].length != length) continue;
                byte[] descriptor = DESCRIPTORS[kind];
                int index = 0;
                while (index < length && bytes[offset + 2 + index] == descriptor[index]) index++;
                if (index == length) seen |= 1 << kind;
            }
        }
        if (seen == 0) return false;
        boolean dist = (seen & 3) != 0;
        boolean holders = (seen & 4) != 0;
        boolean subscribers = (seen & 8) != 0;
        VarHandle side = dist ? DIST.get(distCleaner) : null;
        boolean publicClass = (reader.getAccess() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                == Opcodes.ACC_PUBLIC;
        try {
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    // RuntimeDistCleaner also strips matching class-level annotations.
                    if (dist && visible && (ONLY_IN.equals(descriptor) || ONLY_INS.equals(descriptor))) {
                        throw REWRITE_REQUIRED;
                    }
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    boolean holderField = holders && (access & HOLDER_FLAGS) == HOLDER_FLAGS && descriptor.startsWith("L");
                    if (!dist && !holderField) return null;
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!visible) return null;
                            if (holderField && OBJECT_HOLDER.equals(annotation)) throw REWRITE_REQUIRED;
                            return dist ? distAnnotation(annotation, side) : null;
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!dist && !subscribers) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!visible) return null;
                            if (subscribers && SUBSCRIBE_EVENT.equals(annotation)
                                    && (!publicClass || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                                        != Opcodes.ACC_PUBLIC)) throw REWRITE_REQUIRED;
                            return dist ? distAnnotation(annotation, side) : null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return false;
        } catch (RewriteRequired required) {
            return true;
        }
    }

    private static AnnotationVisitor distAnnotation(String descriptor, VarHandle side) {
        if (!ONLY_IN.equals(descriptor) && !ONLY_INS.equals(descriptor)) return null;
        return new AnnotationNode(Opcodes.ASM9, descriptor) {
            @Override
            public void visitEnd() {
                if (ONLY_IN.equals(desc)) {
                    if (removes(this, side)) throw REWRITE_REQUIRED;
                } else if (values != null) {
                    List<?> nested = (List<?>) values.get(values.indexOf("value") + 1);
                    if (nested != null) {
                        for (Object entry : nested) {
                            AnnotationNode annotation = (AnnotationNode) entry;
                            if (ONLY_IN.equals(annotation.desc) && removes(annotation, side)) throw REWRITE_REQUIRED;
                        }
                    }
                }
            }
        };
    }

    private static boolean removes(AnnotationNode annotation, VarHandle side) {
        return !annotation.values.contains("_interface")
                && !Objects.equals(((String[]) annotation.values.get(annotation.values.indexOf("value") + 1))[1],
                        (String) side.get());
    }

    /** Stops the read-only visitor once the native path is known to be required. */
    private static final class RewriteRequired extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RewriteRequired() {
            super(null, null, false, false);
        }
    }
}

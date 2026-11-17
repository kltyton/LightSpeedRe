package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class JdkModuleGraphPatch {
    private static final String DESCRIPTOR = "(Ljava/lang/module/Configuration;)Ljava/util/Map;";
    private static final String CHECK_EXPORT_DESCRIPTOR = "(Ljava/util/Map;)V";

    private JdkModuleGraphPatch() {
    }

    public static byte[] methodBytes(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode method = AsmPatchSupport.method(node, "makeGraph", DESCRIPTOR);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "GraphFingerprint", null, "java/lang/Object", null);
        method.maxStack = 0;
        method.maxLocals = 0;
        method.accept(writer.visitMethod(method.access, method.name, method.desc, null, null));
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode graph = AsmPatchSupport.method(node, "makeGraph", DESCRIPTOR);
        MethodInsnNode values = null;
        TypeInsnNode toAdd = null;
        int allocations = 0;
        for (AbstractInsnNode instruction : graph.instructions) {
            if (instruction instanceof MethodInsnNode method && method.owner.equals("java/util/Map")
                    && method.name.equals("values") && method.desc.equals("()Ljava/util/Collection;")) {
                values = method;
            }
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/ArrayList")) {
                toAdd = type;
                allocations++;
            }
        }
        if (values == null || toAdd == null || allocations != 1
                || !(values.getPrevious() instanceof VarInsnNode graphLoad)
                || graphLoad.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException("unsupported JDK graph propagation loop");
        }
        MethodInsnNode transitiveGet = null;
        for (AbstractInsnNode instruction = values.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode method && method.owner.equals("java/util/Map")
                    && method.name.equals("get") && method.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
                transitiveGet = method;
                break;
            }
        }
        if (transitiveGet == null || !(transitiveGet.getPrevious() instanceof VarInsnNode)
                || !(transitiveGet.getPrevious().getPrevious() instanceof VarInsnNode transitiveLoad)
                || transitiveLoad.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException("missing JDK transitive graph lookup");
        }
        int local = graph.maxLocals;
        graph.instructions.insertBefore(toAdd, explicitReadSets(graphLoad.var, transitiveLoad.var, local));
        graphLoad.var = local;
        graph.instructions.remove(values);
        graph.maxLocals += 7;

        MethodNode checkExport = AsmPatchSupport.method(node, "checkExportSuppliers", CHECK_EXPORT_DESCRIPTOR);
        if (checkExport != null && checkExport.instructions.getFirst() != null) {
            int base = checkExport.maxLocals;
            checkExport.instructions.insertBefore(checkExport.instructions.getFirst(), checkExportPrefix(base));
            checkExport.maxLocals += 14;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.equals("makeGraph") && descriptor.equals(DESCRIPTOR)) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    graph.accept(visitor);
                    return null;
                }
                if (checkExport != null && name.equals("checkExportSuppliers")
                        && descriptor.equals(CHECK_EXPORT_DESCRIPTOR)) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    checkExport.accept(visitor);
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static InsnList explicitReadSets(int graph, int transitive, int local) {
        MethodNode code = new MethodNode();
        Label next = new Label();
        Label automatic = new Label();
        Label read = new Label();
        Label done = new Label();
        code.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        code.visitInsn(Opcodes.DUP);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        code.visitVarInsn(Opcodes.ASTORE, local);
        code.visitVarInsn(Opcodes.ALOAD, graph);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true);
        iterator(code, local + 1);
        code.visitLabel(next);
        hasNext(code, local + 1, done);
        next(code, local + 1);
        code.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map$Entry");
        code.visitVarInsn(Opcodes.ASTORE, local + 2);
        code.visitVarInsn(Opcodes.ALOAD, local + 2);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        code.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/module/ResolvedModule");
        code.visitVarInsn(Opcodes.ASTORE, local + 3);
        code.visitVarInsn(Opcodes.ALOAD, local + 2);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
        code.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set");
        code.visitVarInsn(Opcodes.ASTORE, local + 4);
        code.visitVarInsn(Opcodes.ALOAD, local + 3);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/module/ResolvedModule", "descriptor",
                "()Ljava/lang/module/ModuleDescriptor;", false);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/module/ModuleDescriptor", "isAutomatic", "()Z", false);
        code.visitJumpInsn(Opcodes.IFNE, automatic);
        code.visitVarInsn(Opcodes.ALOAD, local);
        code.visitVarInsn(Opcodes.ALOAD, local + 4);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        code.visitInsn(Opcodes.POP);
        code.visitJumpInsn(Opcodes.GOTO, next);

        // Automatic modules already read every selected and parent module. Preserve
        // the self-read edge that JDK 21 adds when another module requires them transitively.
        code.visitLabel(automatic);
        code.visitVarInsn(Opcodes.ALOAD, local + 4);
        iterator(code, local + 5);
        code.visitLabel(read);
        hasNext(code, local + 5, next);
        code.visitVarInsn(Opcodes.ALOAD, transitive);
        next(code, local + 5);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        code.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set");
        code.visitVarInsn(Opcodes.ASTORE, local + 6);
        code.visitVarInsn(Opcodes.ALOAD, local + 6);
        code.visitJumpInsn(Opcodes.IFNULL, read);
        code.visitVarInsn(Opcodes.ALOAD, local + 6);
        code.visitVarInsn(Opcodes.ALOAD, local + 3);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true);
        code.visitJumpInsn(Opcodes.IFEQ, read);
        code.visitVarInsn(Opcodes.ALOAD, local + 4);
        code.visitVarInsn(Opcodes.ALOAD, local + 3);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
        code.visitInsn(Opcodes.POP);
        code.visitJumpInsn(Opcodes.GOTO, next);
        code.visitLabel(done);
        return code.instructions;
    }

    private static void iterator(MethodVisitor code, int destination) {
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true);
        code.visitVarInsn(Opcodes.ASTORE, destination);
    }

    private static void hasNext(MethodVisitor code, int iterator, Label end) {
        code.visitVarInsn(Opcodes.ALOAD, iterator);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        code.visitJumpInsn(Opcodes.IFEQ, end);
    }

    private static void next(MethodVisitor code, int iterator) {
        code.visitVarInsn(Opcodes.ALOAD, iterator);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
    }

    private static InsnList checkExportPrefix(int base) {
        String resolvedModule = "java/lang/module/ResolvedModule";
        String descriptor = "java/lang/module/ModuleDescriptor";
        String exports = "java/lang/module/ModuleDescriptor$Exports";
        MethodNode mv = new MethodNode();
        Label originalCheck = new Label();
        Label findA0Loop = new Label();
        Label afterFindA0 = new Label();
        Label qualOuterLoop = new Label();
        Label qualInnerLoop = new Label();
        Label afterQual = new Label();
        Label remLoop = new Label();
        Label checkKeepExtra = new Label();
        Label addToRem = new Label();
        Label afterRem = new Label();

        int a0 = base;
        int reads0 = base + 1;
        int iter = base + 2;
        int entry = base + 3;
        int mod = base + 4;
        int baseSet = base + 5;
        int qualTargets = base + 6;
        int desc = base + 7;
        int expIter = base + 8;
        int exp = base + 9;
        int filtered = base + 10;
        int reads = base + 11;
        int keptExtras = base + 12;

        // if (graph.size() <= 16) proceed directly with original graph
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true);
        mv.visitIntInsn(Opcodes.BIPUSH, 16);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, originalCheck);

        // Find first automatic module (a0, reads0)
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, a0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, reads0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true);
        iterator(mv, iter);
        mv.visitLabel(findA0Loop);
        hasNext(mv, iter, afterFindA0);
        next(mv, iter);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(Opcodes.ASTORE, entry);
        mv.visitVarInsn(Opcodes.ALOAD, entry);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, resolvedModule);
        mv.visitVarInsn(Opcodes.ASTORE, mod);
        mv.visitVarInsn(Opcodes.ALOAD, mod);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, resolvedModule, "descriptor", "()L" + descriptor + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, descriptor, "isAutomatic", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, findA0Loop);
        mv.visitVarInsn(Opcodes.ALOAD, mod);
        mv.visitVarInsn(Opcodes.ASTORE, a0);
        mv.visitVarInsn(Opcodes.ALOAD, entry);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set");
        mv.visitVarInsn(Opcodes.ASTORE, reads0);
        mv.visitJumpInsn(Opcodes.GOTO, afterFindA0);

        mv.visitLabel(afterFindA0);
        mv.visitVarInsn(Opcodes.ALOAD, a0);
        mv.visitJumpInsn(Opcodes.IFNULL, originalCheck);

        // baseSet = new HashSet(reads0); baseSet.add(a0);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, reads0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "(Ljava/util/Collection;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, baseSet);
        mv.visitVarInsn(Opcodes.ALOAD, baseSet);
        mv.visitVarInsn(Opcodes.ALOAD, a0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);

        // qualifiedTargets = new HashSet()
        mv.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, qualTargets);
        mv.visitVarInsn(Opcodes.ALOAD, baseSet);
        iterator(mv, iter);
        mv.visitLabel(qualOuterLoop);
        hasNext(mv, iter, afterQual);
        next(mv, iter);
        mv.visitTypeInsn(Opcodes.CHECKCAST, resolvedModule);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, resolvedModule, "descriptor", "()L" + descriptor + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, desc);
        mv.visitVarInsn(Opcodes.ALOAD, desc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, descriptor, "isAutomatic", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, qualOuterLoop);
        mv.visitVarInsn(Opcodes.ALOAD, desc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, descriptor, "exports", "()Ljava/util/Set;", false);
        iterator(mv, expIter);
        mv.visitLabel(qualInnerLoop);
        hasNext(mv, expIter, qualOuterLoop);
        next(mv, expIter);
        mv.visitTypeInsn(Opcodes.CHECKCAST, exports);
        mv.visitVarInsn(Opcodes.ASTORE, exp);
        mv.visitVarInsn(Opcodes.ALOAD, exp);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, exports, "isQualified", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, qualInnerLoop);
        mv.visitVarInsn(Opcodes.ALOAD, qualTargets);
        mv.visitVarInsn(Opcodes.ALOAD, exp);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, exports, "targets", "()Ljava/util/Set;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, qualInnerLoop);

        // If a0 itself is targeted by a qualified export, keep original graph
        mv.visitLabel(afterQual);
        mv.visitVarInsn(Opcodes.ALOAD, qualTargets);
        mv.visitVarInsn(Opcodes.ALOAD, a0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, resolvedModule, "descriptor", "()L" + descriptor + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, descriptor, "name", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true);
        mv.visitJumpInsn(Opcodes.IFNE, originalCheck);

        // Build filtered graph: keep a0 + 2 extra equivalent automatic modules + all non-equivalent modules
        mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, filtered);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, keptExtras);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true);
        iterator(mv, iter);
        mv.visitLabel(remLoop);
        hasNext(mv, iter, afterRem);
        next(mv, iter);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(Opcodes.ASTORE, entry);
        mv.visitVarInsn(Opcodes.ALOAD, entry);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, resolvedModule);
        mv.visitVarInsn(Opcodes.ASTORE, mod);
        mv.visitVarInsn(Opcodes.ALOAD, entry);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set");
        mv.visitVarInsn(Opcodes.ASTORE, reads);
        mv.visitVarInsn(Opcodes.ALOAD, mod);
        mv.visitVarInsn(Opcodes.ALOAD, a0);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, addToRem);
        mv.visitVarInsn(Opcodes.ALOAD, mod);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, resolvedModule, "descriptor", "()L" + descriptor + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, desc);
        mv.visitVarInsn(Opcodes.ALOAD, desc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, descriptor, "isAutomatic", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, addToRem);
        mv.visitVarInsn(Opcodes.ALOAD, qualTargets);
        mv.visitVarInsn(Opcodes.ALOAD, desc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, descriptor, "name", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true);
        mv.visitJumpInsn(Opcodes.IFNE, addToRem);
        mv.visitVarInsn(Opcodes.ALOAD, baseSet);
        mv.visitVarInsn(Opcodes.ALOAD, mod);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, addToRem);
        mv.visitVarInsn(Opcodes.ALOAD, reads);
        mv.visitVarInsn(Opcodes.ALOAD, reads0);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, checkKeepExtra);
        mv.visitVarInsn(Opcodes.ALOAD, reads);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true);
        mv.visitVarInsn(Opcodes.ALOAD, baseSet);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, addToRem);
        mv.visitVarInsn(Opcodes.ALOAD, baseSet);
        mv.visitVarInsn(Opcodes.ALOAD, reads);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "containsAll", "(Ljava/util/Collection;)Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, addToRem);

        mv.visitLabel(checkKeepExtra);
        mv.visitVarInsn(Opcodes.ILOAD, keptExtras);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, remLoop);
        mv.visitIincInsn(keptExtras, 1);

        mv.visitLabel(addToRem);
        mv.visitVarInsn(Opcodes.ALOAD, filtered);
        mv.visitVarInsn(Opcodes.ALOAD, mod);
        mv.visitVarInsn(Opcodes.ALOAD, reads);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, remLoop);

        mv.visitLabel(afterRem);
        mv.visitVarInsn(Opcodes.ALOAD, filtered);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitLabel(originalCheck);
        return mv.instructions;
    }
}

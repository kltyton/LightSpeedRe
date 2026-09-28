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

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("makeGraph") && descriptor.equals(DESCRIPTOR)) {
                    graph.accept(visitor);
                    return null;
                }
                return visitor;
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
}

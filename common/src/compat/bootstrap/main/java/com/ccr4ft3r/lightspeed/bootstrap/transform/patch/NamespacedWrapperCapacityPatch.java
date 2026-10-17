package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class NamespacedWrapperCapacityPatch {
    private static final String LIST = "it/unimi/dsi/fastutil/objects/ObjectList";
    private static final String ARRAY_LIST = "it/unimi/dsi/fastutil/objects/ObjectArrayList";
    private static final String ADDED = "(Lnet/minecraftforge/registries/RegistryManager;I"
            + "Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Ljava/lang/Object;)"
            + "Lnet/minecraft/core/Holder$Reference;";
    private static final String RESERVE = "lightspeed$ensureHolderCapacity";
    private static final String RESERVE_DESC = "(L" + LIST + ";I)V";

    private NamespacedWrapperCapacityPatch() { }

    public static byte[] methodBytes(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode method = AsmPatchSupport.method(node, "onAdded", ADDED);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "HolderFingerprint", null, "java/lang/Object", null);
        method.maxStack = 0;
        method.maxLocals = 0;
        method.accept(writer.visitMethod(method.access, method.name, method.desc, null, null));
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode added = AsmPatchSupport.method(node, "onAdded", ADDED);
        MethodInsnNode resize = AsmPatchSupport.uniqueInvocation(added, Opcodes.INVOKEINTERFACE,
                LIST, "size", "(I)V");
        InsnList reserve = new InsnList();
        reserve.add(new InsnNode(Opcodes.DUP2));
        reserve.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, RESERVE, RESERVE_DESC, false));
        added.instructions.insertBefore(resize, reserve);

        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                RESERVE, RESERVE_DESC, null, null);
        helper.visitCode();
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitTypeInsn(Opcodes.CHECKCAST, ARRAY_LIST);
        helper.visitVarInsn(Opcodes.ASTORE, 2);
        helper.visitVarInsn(Opcodes.ALOAD, 2);
        helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "elements", "()[Ljava/lang/Object;", false);
        helper.visitInsn(Opcodes.ARRAYLENGTH);
        helper.visitVarInsn(Opcodes.ISTORE, 3);
        helper.visitVarInsn(Opcodes.ALOAD, 2);
        helper.visitVarInsn(Opcodes.ILOAD, 1);
        helper.visitVarInsn(Opcodes.ILOAD, 3);
        // Reserve 1.5x capacity only when the requested ID exceeds the backing array.
        helper.visitVarInsn(Opcodes.ILOAD, 3);
        helper.visitInsn(Opcodes.ICONST_1);
        helper.visitInsn(Opcodes.ISHR);
        helper.visitVarInsn(Opcodes.ILOAD, 3);
        helper.visitVarInsn(Opcodes.ILOAD, 1);
        helper.visitInsn(Opcodes.ISUB);
        helper.visitIntInsn(Opcodes.BIPUSH, 31);
        helper.visitInsn(Opcodes.ISHR);
        helper.visitInsn(Opcodes.IAND);
        helper.visitInsn(Opcodes.IADD);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
        helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "ensureCapacity", "(I)V", false);
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(6, 4);
        helper.visitEnd();
        node.methods.add(helper);
        return AsmPatchSupport.write(node);
    }
}

package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ClassPassThroughPatch {
    private ClassPassThroughPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "transform", "([BLjava/lang/String;Ljava/lang/String;)[B");
        TypeInsnNode classNode = null;
        String typeOwner = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getClassName")
                    && call.desc.equals("()Ljava/lang/String;") && call.owner.endsWith("/Type")) {
                typeOwner = call.owner;
            }
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.endsWith("/tree/ClassNode")) {
                if (classNode != null) throw new IllegalStateException("multiple class-tree allocations");
                classNode = type;
            }
        }
        if (classNode == null || typeOwner == null) throw new IllegalStateException("missing class-tree metadata");
        LabelNode original = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
        fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, typeOwner, "getClassName", "()Ljava/lang/String;", false));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 6));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 7));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "auditTrail",
                "Lcpw/mods/modlauncher/TransformerAuditTrail;"));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "transformingClassLoader",
                "Lcpw/mods/modlauncher/TransformingClassLoader;"));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "passThroughClass",
                "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/util/Map;ZLjava/lang/Object;Ljava/lang/ClassLoader;)Z", false));
        fast.add(new JumpInsnNode(Opcodes.IFEQ, original));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new InsnNode(Opcodes.ARETURN));
        fast.add(original);
        fast.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(classNode, fast);
        return AsmPatchSupport.write(node);
    }
}

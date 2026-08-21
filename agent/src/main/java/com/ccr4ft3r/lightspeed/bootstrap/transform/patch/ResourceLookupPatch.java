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
import org.objectweb.asm.tree.VarInsnNode;

public final class ResourceLookupPatch {
    private ResourceLookupPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        return apply(bytes, false);
    }

    public static byte[] applyWithRegistration(byte[] bytes) {
        return apply(bytes, true);
    }

    private static byte[] apply(byte[] bytes, boolean registerRoot) {
        ClassNode node = AsmPatchSupport.read(bytes);
        if (registerRoot) {
            registerResourceRoot(node);
        }
        MethodNode method = AsmPatchSupport.method(node, "findFile", "(Ljava/lang/String;)Ljava/util/Optional;");
        LabelNode proceed = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getRootPath", "()Ljava/nio/file/Path;", false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getPrimaryPath", "()Ljava/nio/file/Path;", false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "mightContain",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/lang/String;)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
        guard.add(new InsnNode(Opcodes.ARETURN));
        guard.add(proceed);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(method.instructions.getFirst(), guard);
        return AsmPatchSupport.write(node);
    }

    private static void registerResourceRoot(ClassNode node) {
        MethodNode constructor = AsmPatchSupport.method(node, "<init>",
                "(Ljava/util/function/Supplier;Ljava/util/function/Function;Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)V");
        FieldInsnNode filesystemWrite = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(node.name)
                    && field.name.equals("filesystem")) {
                if (filesystemWrite != null) {
                    throw new IllegalStateException("duplicate filesystem assignment");
                }
                filesystemWrite = field;
            }
        }
        if (filesystemWrite == null) {
            throw new IllegalStateException("missing filesystem assignment");
        }

        InsnList registration = new InsnList();
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "filesystem",
                "Lcpw/mods/niofs/union/UnionFileSystem;"));
        registration.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionFileSystem",
                "getRoot", "()Ljava/nio/file/Path;", false));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registration.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "filesystem",
                "Lcpw/mods/niofs/union/UnionFileSystem;"));
        registration.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionFileSystem",
                "getPrimaryPath", "()Ljava/nio/file/Path;", false));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 3));
        registration.add(new VarInsnNode(Opcodes.ALOAD, 4));
        registration.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "registerResourceRoot",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)V",
                false));
        constructor.instructions.insert(filesystemWrite, registration);
    }
}

package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ServiceDiscoveryPatch {
    private static final String PATH_DESCRIPTOR = "(Ljava/nio/file/Path;)Z";

    private ServiceDiscoveryPatch() {
    }

    public static byte[] forge(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "visitFile", "(Ljava/nio/file/Path;)V");
        MethodInsnNode target = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKESTATIC,
                "cpw/mods/jarhandling/SecureJar", "from",
                "([Ljava/nio/file/Path;)Lcpw/mods/jarhandling/SecureJar;");
        method.instructions.insertBefore(method.instructions.getFirst(), guard(Opcodes.RETURN));
        return AsmPatchSupport.write(node);
    }

    public static byte[] neoForge(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "shouldLoadInServiceLayer", PATH_DESCRIPTOR);
        MethodInsnNode target = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKESTATIC,
                "net/neoforged/fml/loading/TransformerDiscovererConstants", "shouldLoadInServiceLayer",
                PATH_DESCRIPTOR);
        method.instructions.insertBefore(method.instructions.getFirst(), guard(Opcodes.IRETURN));
        return AsmPatchSupport.write(node);
    }

    private static InsnList guard(int returnOpcode) {
        LabelNode proceed = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "mayProvideTransformerService", PATH_DESCRIPTOR, false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        if (returnOpcode == Opcodes.IRETURN) {
            guard.add(new InsnNode(Opcodes.ICONST_0));
        }
        guard.add(new InsnNode(returnOpcode));
        guard.add(proceed);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        return guard;
    }
}

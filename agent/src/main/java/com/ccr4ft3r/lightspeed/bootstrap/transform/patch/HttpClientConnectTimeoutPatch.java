package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class HttpClientConnectTimeoutPatch {
    private HttpClientConnectTimeoutPatch() { }

    public static byte[] apply(byte[] bytes) {
        var node = AsmPatchSupport.read(bytes);
        MethodNode timeout = AsmPatchSupport.method(node, "connectTimeout", "()Ljava/util/Optional;");
        int original = timeout.maxLocals++;
        int returns = 0;
        for (AbstractInsnNode instruction : timeout.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList limit = new InsnList();
            limit.add(new VarInsnNode(Opcodes.ASTORE, original));
            limit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                    "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false));
            limit.add(new LdcInsnNode("com.ccr4ft3r.lightspeed.bootstrap.runtime.BootstrapHooks"));
            limit.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false));
            limit.add(new LdcInsnNode("limitHttpClientConnect"));
            limit.add(new InsnNode(Opcodes.ICONST_1));
            limit.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            limit.add(new InsnNode(Opcodes.DUP));
            limit.add(new InsnNode(Opcodes.ICONST_0));
            limit.add(new LdcInsnNode(Type.getType("Ljava/util/Optional;")));
            limit.add(new InsnNode(Opcodes.AASTORE));
            limit.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
            limit.add(new InsnNode(Opcodes.ACONST_NULL));
            limit.add(new InsnNode(Opcodes.ICONST_1));
            limit.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            limit.add(new InsnNode(Opcodes.DUP));
            limit.add(new InsnNode(Opcodes.ICONST_0));
            limit.add(new VarInsnNode(Opcodes.ALOAD, original));
            limit.add(new InsnNode(Opcodes.AASTORE));
            limit.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
            limit.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Optional"));
            timeout.instructions.insertBefore(instruction, limit);
            returns++;
        }
        if (returns != 1) throw new IllegalStateException("Unexpected HttpClient timeout getter");
        return AsmPatchSupport.write(node);
    }
}

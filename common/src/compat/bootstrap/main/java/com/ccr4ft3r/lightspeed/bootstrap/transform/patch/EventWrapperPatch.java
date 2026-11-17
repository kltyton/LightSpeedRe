package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class EventWrapperPatch {
    private static final String DESCRIPTOR = "(Ljava/lang/reflect/Method;)Ljava/lang/Class;";

    private EventWrapperPatch() {
    }

    /** Keeps distinct nested listener classes and overloaded callbacks in separate generated classes. */
    public static byte[] applyFactoryIdentity(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        if (!node.name.equals("net/minecraftforge/eventbus/ClassLoaderFactory")
                || !node.superName.equals("java/lang/Object")
                || !node.interfaces.contains("net/minecraftforge/eventbus/IEventListenerFactory")) {
            throw new IllegalStateException("Unsupported EventBus class-loader factory ABI");
        }
        var factories = node.methods.stream().filter(method -> method.name.startsWith("lambda$createWrapper$")
                && method.desc.equals("(Ljava/lang/reflect/Method;)Lorg/objectweb/asm/tree/ClassNode;")
                && (method.access & Opcodes.ACC_STATIC) == 0).toList();
        if (factories.size() != 1) {
            throw new IllegalStateException("Expected one EventBus wrapper node factory");
        }
        MethodNode factory = factories.get(0);
        MethodInsnNode naming = AsmPatchSupport.uniqueInvocation(factory, Opcodes.INVOKEVIRTUAL,
                node.name, "getUniqueName", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
        AsmPatchSupport.uniqueInvocation(factory, Opcodes.INVOKESTATIC, node.name, "transformNode",
                "(Ljava/lang/String;Ljava/lang/reflect/Method;Lorg/objectweb/asm/tree/ClassNode;)V");
        for (AbstractInsnNode instruction : factory.instructions) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(AsmPatchSupport.HOOK_OWNER) && call.name.equals("eventWrapperName")
                    && call.desc.equals("(Ljava/lang/String;Ljava/lang/reflect/Method;)Ljava/lang/String;")) {
                return bytes;
            }
        }
        InsnList identity = new InsnList();
        identity.add(new VarInsnNode(Opcodes.ALOAD, 1));
        identity.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "eventWrapperName",
                "(Ljava/lang/String;Ljava/lang/reflect/Method;)Ljava/lang/String;", false));
        factory.instructions.insert(naming, identity);
        return AsmPatchSupport.write(node);
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "createWrapper", DESCRIPTOR);
        LabelNode original = new LabelNode();
        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "canUseDirectEventWrapper", "(Ljava/lang/reflect/Method;)Z", false));
        fastPath.add(new JumpInsnNode(Opcodes.IFEQ, original));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "createWrapper", DESCRIPTOR, false));
        fastPath.add(new InsnNode(Opcodes.ARETURN));
        fastPath.add(original);
        fastPath.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(method.instructions.getFirst(), fastPath);
        return AsmPatchSupport.write(node);
    }
}

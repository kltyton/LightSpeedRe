package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Replaces NeoForge {@code LockHelper} copy-on-write snapshot copies with a thread-safe
 * {@link java.util.concurrent.ConcurrentHashMap} backing map exposed through an unmodifiable view.
 */
public final class BusLockHelperPatch {
    private BusLockHelperPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode init = AsmPatchSupport.method(node, "<init>", "(Ljava/util/function/IntFunction;)V");
        clearMethod(init);
        InsnList initCode = new InsnList();
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "lock", "Ljava/lang/Object;"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "mapConstructor",
                "Ljava/util/function/IntFunction;"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new IntInsnNode(Opcodes.BIPUSH, 32));
        initCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap",
                "<init>", "(I)V", false));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new VarInsnNode(Opcodes.ASTORE, 2));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "backingMap", "Ljava/util/Map;"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        initCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections",
                "unmodifiableMap", "(Ljava/util/Map;)Ljava/util/Map;", false));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "readOnlyView", "Ljava/util/Map;"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        init.instructions.add(initCode);

        MethodNode getReadMap = AsmPatchSupport.method(node, "getReadMap", "()Ljava/util/Map;");
        clearMethod(getReadMap);
        getReadMap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getReadMap.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "readOnlyView", "Ljava/util/Map;"));
        getReadMap.instructions.add(new InsnNode(Opcodes.ARETURN));

        MethodNode get = AsmPatchSupport.method(node, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        clearMethod(get);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "backingMap", "Ljava/util/Map;"));
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        get.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map",
                "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        get.instructions.add(new InsnNode(Opcodes.ARETURN));

        MethodNode containsKey = AsmPatchSupport.method(node, "containsKey", "(Ljava/lang/Object;)Z");
        clearMethod(containsKey);
        containsKey.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        containsKey.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "backingMap", "Ljava/util/Map;"));
        containsKey.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        containsKey.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map",
                "containsKey", "(Ljava/lang/Object;)Z", true));
        containsKey.instructions.add(new InsnNode(Opcodes.IRETURN));

        removeReadOnlyViewNullReset(AsmPatchSupport.method(node, "computeIfAbsent",
                "(Ljava/lang/Object;Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/lang/Object;"),
                node.name);
        removeReadOnlyViewNullReset(AsmPatchSupport.method(node, "clearAll", "()V"), node.name);

        return AsmPatchSupport.write(node);
    }

    private static void clearMethod(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
    }

    private static void removeReadOnlyViewNullReset(MethodNode method, String owner) {
        AbstractInsnNode target = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("readOnlyView")
                    && field.desc.equals("Ljava/util/Map;")
                    && field.getPrevious() != null
                    && field.getPrevious().getOpcode() == Opcodes.ACONST_NULL
                    && field.getPrevious().getPrevious() instanceof VarInsnNode loadThis
                    && loadThis.getOpcode() == Opcodes.ALOAD
                    && loadThis.var == 0) {
                target = field;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("missing readOnlyView null reset in " + owner + '.' + method.name);
        }
        AbstractInsnNode nullInsn = target.getPrevious();
        AbstractInsnNode aloadInsn = nullInsn.getPrevious();
        method.instructions.remove(aloadInsn);
        method.instructions.remove(nullInsn);
        method.instructions.remove(target);
    }
}

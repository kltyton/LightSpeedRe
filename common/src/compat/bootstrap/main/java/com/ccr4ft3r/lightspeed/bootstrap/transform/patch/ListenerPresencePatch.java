package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ListenerPresencePatch {
    private static final String LISTENER = "Lnet/minecraftforge/eventbus/api/IEventListener;";
    private static final String MEMBERS = "lightspeed$registered";

    private ListenerPresencePatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                MEMBERS, "Ljava/util/Set;", null, null));

        MethodNode register = AsmPatchSupport.method(node, "register",
                "(Lnet/minecraftforge/eventbus/api/EventPriority;" + LISTENER + ")V");
        MethodInsnNode acquired = AsmPatchSupport.uniqueInvocation(register, Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/Semaphore", "acquireUninterruptibly", "()V");
        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 0));
        record.add(new VarInsnNode(Opcodes.ALOAD, 2));
        record.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "lightspeed$record",
                "(" + LISTENER + ")V", false));
        register.instructions.insert(acquired, record);

        MethodNode unregister = AsmPatchSupport.method(node, "unregister", "(" + LISTENER + ")V");
        LabelNode present = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "lightspeed$shouldUnregister",
                "(" + LISTENER + ")Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, present));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(present);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        unregister.instructions.insert(guard);

        MethodNode dispose = AsmPatchSupport.method(node, "dispose", "()V");
        for (AbstractInsnNode instruction : dispose.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new InsnNode(Opcodes.ACONST_NULL));
            clear.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, MEMBERS, "Ljava/util/Set;"));
            dispose.instructions.insertBefore(instruction, clear);
        }

        node.methods.add(record(node.name));
        node.methods.add(shouldUnregister(node.name));
        return AsmPatchSupport.write(node);
    }

    private static MethodNode record(String owner) {
        // Keep an overapproximation: one unregister may leave another registration
        // of the same listener, and stale weak entries only take the original path.
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "lightspeed$record", "(" + LISTENER + ")V", null, null);
        LabelNode ready = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MEMBERS, "Ljava/util/Set;"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/WeakHashMap"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/WeakHashMap",
                "<init>", "()V", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections",
                "newSetFromMap", "(Ljava/util/Map;)Ljava/util/Set;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections",
                "synchronizedSet", "(Ljava/util/Set;)Ljava/util/Set;", false));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MEMBERS, "Ljava/util/Set;"));
        method.instructions.add(ready);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MEMBERS, "Ljava/util/Set;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set",
                "add", "(Ljava/lang/Object;)Z", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = 2;
        method.maxStack = 4;
        return method;
    }

    private static MethodNode shouldUnregister(String owner) {
        // When a writer holds the semaphore, the original path waits for that
        // registration instead of deciding from a membership snapshot.
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "lightspeed$shouldUnregister", "(" + LISTENER + ")Z", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MEMBERS, "Ljava/util/Set;"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections",
                "emptySet", "()Ljava/util/Set;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects",
                "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Set"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set",
                "contains", "(Ljava/lang/Object;)Z", true));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "writeLock",
                "Ljava/util/concurrent/Semaphore;"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/Semaphore",
                "availablePermits", "()I", false));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new InsnNode(Opcodes.IOR));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 4;
        return method;
    }
}

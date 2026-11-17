package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ListenerPresencePatch {
    private static final String LISTENER = "Lnet/minecraftforge/eventbus/api/IEventListener;";
    private static final String LIST_TYPE = "net/minecraftforge/eventbus/ListenerList";
    private static final String INST_TYPE = "net/minecraftforge/eventbus/ListenerList$ListenerListInst";
    private static final String BUS_ID_FIELD = "lightspeed$busIdPlusOne";
    private static final String MEMBERS = AsmPatchSupport.HOOK_OWNER;

    private ListenerPresencePatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        addBusFieldAndInterfaces(node);

        MethodNode register = AsmPatchSupport.method(node, "register",
                "(Lnet/minecraftforge/eventbus/api/EventPriority;" + LISTENER + ")V");
        MethodInsnNode acquired = AsmPatchSupport.uniqueInvocation(register, Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/Semaphore", "acquireUninterruptibly", "()V");
        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 0));
        record.add(new VarInsnNode(Opcodes.ALOAD, 2));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MEMBERS, "recordListener",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        register.instructions.insert(acquired, record);

        MethodNode unregister = AsmPatchSupport.method(node, "unregister", "(" + LISTENER + ")V");
        LabelNode present = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "writeLock",
                "Ljava/util/concurrent/Semaphore;"));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/Semaphore",
                "availablePermits", "()I", false));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MEMBERS, "shouldUnregisterListener",
                "(Ljava/lang/Object;Ljava/lang/Object;I)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, present));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(present);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        unregister.instructions.insert(guard);

        MethodInsnNode release = AsmPatchSupport.uniqueInvocation(unregister, Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/Semaphore", "release", "()V");
        InsnList reconcile = new InsnList();
        reconcile.add(new VarInsnNode(Opcodes.ALOAD, 0));
        reconcile.add(new VarInsnNode(Opcodes.ALOAD, 0));
        reconcile.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "priorities", "Ljava/util/ArrayList;"));
        reconcile.add(new VarInsnNode(Opcodes.ALOAD, 1));
        reconcile.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MEMBERS, "reconcileListenerUnregister",
                "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V", false));
        unregister.instructions.insertBefore(release, reconcile);

        MethodNode dispose = AsmPatchSupport.method(node, "dispose", "()V");
        for (AbstractInsnNode instruction : dispose.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MEMBERS, "clearListenerMembership",
                    "(Ljava/lang/Object;)V", false));
            dispose.instructions.insertBefore(instruction, clear);
        }
        return AsmPatchSupport.write(node);
    }

    public static byte[] applyToList(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);

        MethodNode register = AsmPatchSupport.method(node, "register",
                "(ILnet/minecraftforge/eventbus/api/EventPriority;" + LISTENER + ")V");
        InsnList markBus = new InsnList();
        markBus.add(new VarInsnNode(Opcodes.ALOAD, 0));
        markBus.add(new FieldInsnNode(Opcodes.GETFIELD, LIST_TYPE, "lists", "[L" + INST_TYPE + ";"));
        markBus.add(new VarInsnNode(Opcodes.ILOAD, 1));
        markBus.add(new InsnNode(Opcodes.AALOAD));
        markBus.add(new VarInsnNode(Opcodes.ILOAD, 1));
        markBus.add(new InsnNode(Opcodes.ICONST_1));
        markBus.add(new InsnNode(Opcodes.IADD));
        markBus.add(new FieldInsnNode(Opcodes.PUTFIELD, INST_TYPE, BUS_ID_FIELD, "I"));
        register.instructions.insert(markBus);

        MethodNode unregisterAll = AsmPatchSupport.method(node, "unregisterAll", "(I" + LISTENER + ")V");
        LabelNode fallback = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ILOAD, 0));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MEMBERS, "unregisterKnownListeners",
                "(ILjava/lang/Object;)Z", false));
        fast.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        fast.add(new InsnNode(Opcodes.RETURN));
        fast.add(fallback);
        fast.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        unregisterAll.instructions.insert(fast);

        return AsmPatchSupport.write(node);
    }

    private static void addBusFieldAndInterfaces(ClassNode node) {
        if (node.fields.stream().noneMatch(field -> field.name.equals(BUS_ID_FIELD))) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, BUS_ID_FIELD, "I", null, null));
        }
        if (!node.interfaces.contains("java/util/function/Consumer")) {
            node.interfaces.add("java/util/function/Consumer");
        }
        if (!node.interfaces.contains("java/util/function/ToIntFunction")) {
            node.interfaces.add("java/util/function/ToIntFunction");
        }
        if (node.methods.stream().noneMatch(m -> m.name.equals("accept") && m.desc.equals("(Ljava/lang/Object;)V"))) {
            MethodNode accept = new MethodNode(Opcodes.ACC_PUBLIC, "accept", "(Ljava/lang/Object;)V", null, null);
            accept.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            accept.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            accept.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraftforge/eventbus/api/IEventListener"));
            accept.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "unregister",
                    "(" + LISTENER + ")V", false));
            accept.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(accept);
        }
        if (node.methods.stream().noneMatch(m -> m.name.equals("applyAsInt") && m.desc.equals("(Ljava/lang/Object;)I"))) {
            MethodNode applyAsInt = new MethodNode(Opcodes.ACC_PUBLIC, "applyAsInt", "(Ljava/lang/Object;)I", null, null);
            applyAsInt.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            applyAsInt.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, BUS_ID_FIELD, "I"));
            applyAsInt.instructions.add(new InsnNode(Opcodes.IRETURN));
            node.methods.add(applyAsInt);
        }
    }
}

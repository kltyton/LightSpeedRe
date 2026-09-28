package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class EventBusPatch {
    private EventBusPatch() {
    }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = AsmPatchSupport.method(node, "getDeclMethod",
                "(Ljava/lang/Class;Ljava/lang/reflect/Method;)Ljava/util/Optional;");
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "declaredEventMethod",
                "(Ljava/lang/Class;Ljava/lang/reflect/Method;)Ljava/util/Optional;", false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);

        String listener = "Lnet/minecraftforge/eventbus/api/IEventListener;";
        MethodNode add = AsmPatchSupport.method(node, "addToListeners",
                "(Ljava/lang/Object;Ljava/lang/Class;" + listener
                        + "Lnet/minecraftforge/eventbus/api/EventPriority;)V");
        MethodInsnNode register = AsmPatchSupport.uniqueInvocation(add, Opcodes.INVOKEVIRTUAL,
                "net/minecraftforge/eventbus/ListenerList", "register",
                "(ILnet/minecraftforge/eventbus/api/EventPriority;" + listener + ")V");
        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new VarInsnNode(Opcodes.ALOAD, 3));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "recordKeyedRegisterListener", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        add.instructions.insert(register, record);

        MethodNode unregister = AsmPatchSupport.method(node, "unregister", "(Ljava/lang/Object;)V");
        MethodInsnNode remove = AsmPatchSupport.uniqueInvocation(unregister, Opcodes.INVOKESTATIC,
                "net/minecraftforge/eventbus/ListenerList", "unregisterAll", "(I" + listener + ")V");
        InsnList forget = new InsnList();
        forget.add(new VarInsnNode(Opcodes.ALOAD, 4));
        forget.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "forgetKeyedRegisterListener", "(Ljava/lang/Object;)V", false));
        unregister.instructions.insert(remove, forget);

        MethodNode post = AsmPatchSupport.method(node, "post",
                "(Lnet/minecraftforge/eventbus/api/Event;Lnet/minecraftforge/eventbus/api/IEventBusInvokeDispatcher;)Z");
        MethodInsnNode invoke = AsmPatchSupport.uniqueInvocation(post, Opcodes.INVOKEINTERFACE,
                "net/minecraftforge/eventbus/api/IEventBusInvokeDispatcher", "invoke",
                "(" + listener + "Lnet/minecraftforge/eventbus/api/Event;)V");
        AbstractInsnNode start = invoke.getPrevious().getPrevious().getPrevious().getPrevious().getPrevious();
        if (!(start instanceof VarInsnNode loadWrapper) || loadWrapper.getOpcode() != Opcodes.ALOAD
                || loadWrapper.var != 2) throw new IllegalStateException("unexpected EventBus dispatch operands");
        LabelNode next = null;
        for (AbstractInsnNode instruction : post.instructions) {
            if (!(instruction instanceof IincInsnNode increment) || increment.var != 4) continue;
            for (AbstractInsnNode before = instruction.getPrevious(); before != null && before.getOpcode() < 0;
                    before = before.getPrevious()) {
                if (before instanceof LabelNode label) next = label;
            }
        }
        if (next == null) throw new IllegalStateException("missing EventBus dispatch continuation");
        LabelNode loop = null;
        for (AbstractInsnNode instruction : post.instructions) {
            if (instruction instanceof IincInsnNode increment && increment.var == 4
                    && instruction.getNext() instanceof JumpInsnNode back
                    && back.getOpcode() == Opcodes.GOTO) loop = back.label;
        }
        if (loop == null) throw new IllegalStateException("missing EventBus dispatch loop");
        MethodInsnNode cancelable = AsmPatchSupport.uniqueInvocation(post, Opcodes.INVOKEVIRTUAL,
                "net/minecraftforge/eventbus/api/Event", "isCancelable", "()Z");
        LabelNode result = null;
        for (AbstractInsnNode before = cancelable.getPrevious().getPrevious(); before != null && before.getOpcode() < 0;
                before = before.getPrevious()) {
            if (before instanceof LabelNode label) result = label;
        }
        if (result == null) throw new IllegalStateException("missing EventBus result continuation");
        InsnList emptyBus = new InsnList();
        emptyBus.add(new VarInsnNode(Opcodes.ALOAD, 1));
        emptyBus.add(new VarInsnNode(Opcodes.ALOAD, 3));
        emptyBus.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "skipEmptyRegisterBus", "(Ljava/lang/Object;[Ljava/lang/Object;)Z", false));
        emptyBus.add(new JumpInsnNode(Opcodes.IFEQ, loop));
        emptyBus.add(new JumpInsnNode(Opcodes.GOTO, result));
        post.instructions.insertBefore(loop, emptyBus);
        InsnList filter = new InsnList();
        filter.add(new VarInsnNode(Opcodes.ALOAD, 1));
        filter.add(new VarInsnNode(Opcodes.ALOAD, 3));
        filter.add(new VarInsnNode(Opcodes.ILOAD, 4));
        filter.add(new InsnNode(Opcodes.AALOAD));
        filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "skipKeyedRegisterListener", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        filter.add(new JumpInsnNode(Opcodes.IFNE, next));
        post.instructions.insertBefore(start, filter);


        return AsmPatchSupport.write(node);
    }
}

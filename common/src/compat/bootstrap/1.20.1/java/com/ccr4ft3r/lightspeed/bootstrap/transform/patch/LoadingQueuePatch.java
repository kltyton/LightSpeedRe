package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;

/** Keeps waiting policy scoped to Forge's actual asynchronous transition. */
public final class LoadingQueuePatch {
    private static final String DRIVEN = "net/minecraftforge/fml/ModWorkManager$DrivenExecutor";
    private static final String WAIT_DESCRIPTOR = "(Lnet/minecraftforge/fml/IModLoadingState;L" + DRIVEN
            + ";Ljava/lang/Runnable;Ljava/util/concurrent/CompletableFuture;)V";
    private static final String DEQUE = "Ljava/util/concurrent/ConcurrentLinkedDeque;";

    private LoadingQueuePatch() { }

    public static byte[] modLoader(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode wait = AsmPatchSupport.method(node, "waitForTransition", WAIT_DESCRIPTOR);
        MethodNode body = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "lightspeed$waitForModTransition", WAIT_DESCRIPTOR, null, null);
        body.instructions = wait.instructions;
        body.tryCatchBlocks = wait.tryCatchBlocks;
        body.localVariables = wait.localVariables;
        body.visibleLocalVariableAnnotations = wait.visibleLocalVariableAnnotations;
        body.invisibleLocalVariableAnnotations = wait.invisibleLocalVariableAnnotations;
        body.maxLocals = wait.maxLocals;
        body.maxStack = wait.maxStack;
        node.methods.add(body);
        wait.instructions = new InsnList();
        wait.tryCatchBlocks = new ArrayList<>();
        wait.localVariables = null;
        wait.visibleLocalVariableAnnotations = null;
        wait.invisibleLocalVariableAnnotations = null;

        LabelNode scoped = new LabelNode();
        InsnList code = wait.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, DRIVEN, "selfDriven", "()Z", true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, scoped));
        loadArguments(code);
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, body.name, body.desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(scoped);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        loadArguments(code);
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        String captured = "(L" + node.name + ";" + WAIT_DESCRIPTOR.substring(1, WAIT_DESCRIPTOR.length() - 1)
                + "Ljava/lang/Runnable;";
        code.add(new InvokeDynamicInsnNode("run", captured, metafactory, Type.getMethodType("()V"),
                new Handle(Opcodes.H_INVOKESPECIAL, node.name, body.name, body.desc, false), Type.getMethodType("()V")));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "waitForModTransition",
                "(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Runnable;)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        return AsmPatchSupport.write(node);
    }

    public static byte[] syncExecutor(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode drive = AsmPatchSupport.method(node, "driveOne", "()Z");
        MethodInsnNode poll = AsmPatchSupport.uniqueInvocation(drive, Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/ConcurrentLinkedDeque", "pollFirst", "()Ljava/lang/Object;");
        poll.setOpcode(Opcodes.INVOKESTATIC);
        poll.owner = AsmPatchSupport.HOOK_OWNER;
        poll.name = "pollModLoadingTask";
        poll.desc = "(" + DEQUE + ")Ljava/lang/Object;";
        poll.itf = false;

        MethodNode execute = AsmPatchSupport.method(node, "execute", "(Ljava/lang/Runnable;)V");
        MethodInsnNode enqueue = AsmPatchSupport.uniqueInvocation(execute, Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/ConcurrentLinkedDeque", "addLast", "(Ljava/lang/Object;)V");
        InsnList signal = new InsnList();
        signal.add(new VarInsnNode(Opcodes.ALOAD, 0));
        signal.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "tasks", DEQUE));
        signal.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "modLoadingTaskEnqueued",
                "(" + DEQUE + ")V", false));
        execute.instructions.insert(enqueue, signal);
        return AsmPatchSupport.write(node);
    }

    private static void loadArguments(InsnList code) {
        for (int local = 0; local <= 4; local++) code.add(new VarInsnNode(Opcodes.ALOAD, local));
    }
}

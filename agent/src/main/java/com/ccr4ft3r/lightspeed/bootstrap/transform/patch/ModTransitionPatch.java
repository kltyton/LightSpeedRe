package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ModTransitionPatch {
    private ModTransitionPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode method = node.methods.stream().filter(value -> value.name.equals("buildTransitionHandler"))
                .findFirst().orElseThrow();
        MethodInsnNode submit = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKESTATIC,
                "java/util/concurrent/CompletableFuture", "runAsync",
                "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        MethodInsnNode complete = AsmPatchSupport.uniqueInvocation(method, Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/CompletableFuture", "whenComplete",
                "(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;");
        int executor = method.maxLocals;
        int action = executor + 1;
        int completion = executor + 2;
        method.maxLocals += 3;
        InsnList defer = new InsnList();
        defer.add(new VarInsnNode(Opcodes.ASTORE, executor));
        defer.add(new VarInsnNode(Opcodes.ASTORE, action));
        method.instructions.insertBefore(submit, defer);
        method.instructions.remove(submit);
        InsnList run = new InsnList();
        run.add(new VarInsnNode(Opcodes.ASTORE, completion));
        run.add(new VarInsnNode(Opcodes.ALOAD, action));
        run.add(new VarInsnNode(Opcodes.ALOAD, executor));
        run.add(new VarInsnNode(Opcodes.ALOAD, completion));
        run.add(new VarInsnNode(Opcodes.ALOAD, 0));
        run.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getModId", "()Ljava/lang/String;", false));
        run.add(new VarInsnNode(Opcodes.ALOAD, 0));
        run.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "modLoadingStage", "Lnet/minecraftforge/fml/ModLoadingStage;"));
        run.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false));
        run.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER, "runModTransition",
                "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;Ljava/util/function/BiConsumer;Ljava/lang/String;Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;", false));
        method.instructions.insertBefore(complete, run);
        method.instructions.remove(complete);
        return AsmPatchSupport.write(node);
    }
}

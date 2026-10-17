package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ModContainerPreparationPatch {
    private ModContainerPreparationPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode gather = node.methods.stream().filter(m -> m.name.equals("gatherAndInitializeMods")).findFirst().orElseThrow();
        MethodInsnNode buildMap = null;
        for (AbstractInsnNode instruction : gather.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode lambda)) continue;
            for (Object argument : lambda.bsmArgs) {
                if (!(argument instanceof Handle handle) || !handle.getOwner().equals(node.name)
                        || !handle.getName().equals("buildMods")) continue;
                AbstractInsnNode next = lambda.getNext();
                while (next.getOpcode() < 0) next = next.getNext();
                if (!(next instanceof MethodInsnNode call) || !call.name.equals("map"))
                    throw new IllegalStateException("Missing buildMods stream mapping");
                if (buildMap != null) throw new IllegalStateException("Duplicate buildMods mapping");
                buildMap = call;
            }
        }
        if (buildMap == null) throw new IllegalStateException("Missing buildMods factory");
        InsnList workers = new InsnList();
        workers.add(new VarInsnNode(Opcodes.ALOAD, 2));
        workers.add(new VarInsnNode(Opcodes.ALOAD, 3));
        gather.instructions.insertBefore(buildMap, workers);
        gather.instructions.set(buildMap, new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "prepareModContainers", "(Ljava/util/stream/Stream;Ljava/util/function/Function;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)Ljava/util/stream/Stream;", false));

        MethodNode constructor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        int wrapped = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(node.name) && field.name.equals("loadingExceptions")) {
                constructor.instructions.insertBefore(field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/util/Collections", "synchronizedList", "(Ljava/util/List;)Ljava/util/List;", false));
                wrapped++;
            }
        }
        if (wrapped != 1) throw new IllegalStateException("Unexpected loadingExceptions initialization");

        return AsmPatchSupport.write(node);
    }
}

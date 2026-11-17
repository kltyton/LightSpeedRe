package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Eliminates ModernFix's 25ms+DUMMY_TASK+25ms sleep loop inside {@code ModWorkManagerQueue.pollFirst()},
 * delegating queue coordination directly to {@link com.ccr4ft3r.lightspeed.startup.loading.ModLoadingQueue}.
 */
public final class ModWorkManagerQueueTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "org/embeddedt/modernfix/forge/load/ModWorkManagerQueue";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET.equals(input.name)) {
            return input;
        }
        for (MethodNode method : input.methods) {
            if ("pollFirst".equals(method.name) && "()Ljava/lang/Runnable;".equals(method.desc)) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) {
                    method.localVariables.clear();
                }
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/util/concurrent/ConcurrentLinkedDeque",
                        "pollFirst",
                        "()Ljava/lang/Object;",
                        false
                ));
                method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Runnable"));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                method.maxStack = 1;
                method.maxLocals = 1;
                return input;
            }
        }
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass("org.embeddedt.modernfix.forge.load.ModWorkManagerQueue"));
    }
}

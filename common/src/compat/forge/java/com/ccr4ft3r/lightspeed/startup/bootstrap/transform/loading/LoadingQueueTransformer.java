package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.loading;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Transforms {@code ModLoader.waitForTransition} and {@code ModWorkManager$SyncExecutor.execute}
 * in {@code Layer.GAME} to use LockSupport signal-driven coordination without polling stalls.
 */
public final class LoadingQueueTransformer implements ITransformer<ClassNode> {
    private static final String MOD_LOADER = "net/minecraftforge/fml/ModLoader";
    private static final String SYNC_EXECUTOR = "net/minecraftforge/fml/ModWorkManager$SyncExecutor";
    private static final String MOD_LOADING_QUEUE = "com/ccr4ft3r/lightspeed/startup/loading/ModLoadingQueue";
    private static final String WAIT_DESC = "(Lnet/minecraftforge/fml/IModLoadingState;"
            + "Lnet/minecraftforge/fml/ModWorkManager$DrivenExecutor;"
            + "Ljava/lang/Runnable;Ljava/util/concurrent/CompletableFuture;)V";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (MOD_LOADER.equals(input.name)) {
            patchModLoader(input);
        } else if (SYNC_EXECUTOR.equals(input.name)) {
            patchSyncExecutor(input);
        }
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(
                Target.targetClass("net.minecraftforge.fml.ModLoader"),
                Target.targetClass("net.minecraftforge.fml.ModWorkManager$SyncExecutor")
        );
    }

    private static void patchModLoader(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (!"waitForTransition".equals(method.name) || !WAIT_DESC.equals(method.desc)) {
                continue;
            }
            if (method.tryCatchBlocks.isEmpty()) {
                return;
            }
            LabelNode tryStart = method.tryCatchBlocks.get(0).start;
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                if (tcb.start != tryStart) {
                    return;
                }
            }
            boolean sawIsDone = false;
            boolean sawDrive = false;
            List<AbstractInsnNode> toRemove = new ArrayList<>();
            for (AbstractInsnNode insn = method.instructions.getFirst();
                 insn != null && insn != tryStart;
                 insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    if ("java/util/concurrent/CompletableFuture".equals(call.owner)
                            && "isDone".equals(call.name) && "()Z".equals(call.desc)) {
                        sawIsDone = true;
                    } else if ("net/minecraftforge/fml/ModWorkManager$DrivenExecutor".equals(call.owner)
                            && "drive".equals(call.name) && "(Ljava/lang/Runnable;)V".equals(call.desc)) {
                        sawDrive = true;
                    }
                }
                if (!(insn instanceof LabelNode)) {
                    toRemove.add(insn);
                }
            }
            if (!sawIsDone || !sawDrive) {
                return;
            }
            for (AbstractInsnNode insn : toRemove) {
                if (!(insn instanceof FrameNode) && insn.getType() == AbstractInsnNode.LINE) {
                    continue;
                }
                method.instructions.remove(insn);
            }
            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 4));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 3));
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    MOD_LOADING_QUEUE,
                    "waitForTransition",
                    "(Ljava/util/concurrent/CompletableFuture;Lnet/minecraftforge/fml/ModWorkManager$DrivenExecutor;Ljava/lang/Runnable;)V",
                    false
            ));
            method.instructions.insertBefore(tryStart, replacement);
            return;
        }
    }

    private static void patchSyncExecutor(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (!"execute".equals(method.name) || !"(Ljava/lang/Runnable;)V".equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    method.instructions.insertBefore(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            MOD_LOADING_QUEUE,
                            "signalWork",
                            "()V",
                            false
                    ));
                }
            }
            return;
        }
    }
}

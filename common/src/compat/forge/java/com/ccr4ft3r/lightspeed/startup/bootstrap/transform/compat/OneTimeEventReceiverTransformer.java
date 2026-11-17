package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Rewrites Registrate's {@code OneTimeEventReceiver.lambda$onLoadComplete$0(Triple)} so cleanup
 * targets only the event class hierarchy instead of locking and iterating every {@code ListenerList}.
 */
public final class OneTimeEventReceiverTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "com.tterrag.registrate.util.OneTimeEventReceiver";
    private static final String SUPPORT = "com/ccr4ft3r/lightspeed/startup/compat/OneTimeEventReceiverSupport";
    private static final String TRIPLE_VOID_DESC = "(Lorg/apache/commons/lang3/tuple/Triple;)V";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        for (MethodNode method : input.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0
                    && TRIPLE_VOID_DESC.equals(method.desc)
                    && method.name.startsWith("lambda$onLoadComplete$")
                    && callsEventBusUnregister(method)) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) {
                    method.localVariables.clear();
                }
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        SUPPORT,
                        "unregisterTargeted",
                        TRIPLE_VOID_DESC,
                        false
                ));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                method.maxStack = 1;
                method.maxLocals = 1;
                break;
            }
        }
        return input;
    }

    private static boolean callsEventBusUnregister(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && "net/minecraftforge/eventbus/api/IEventBus".equals(call.owner)
                    && "unregister".equals(call.name)
                    && "(Ljava/lang/Object;)V".equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return Set.of(Target.targetClass(TARGET));
    }
}

package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.TransformAsmHelper;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Presizes Forge's {@code GameData$ClearableObjectIntIdentityMap} ({@code Block.BLOCK_STATE_REGISTRY}
 * backing {@code IdMapper}) to avoid repeated table expansion and rehashing while baking block states.
 */
public final class BlockStateIdMapperTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net.minecraftforge.registries.GameData$ClearableObjectIntIdentityMap";
    private static final String ID_MAPPER_INTERNAL = "net/minecraft/core/IdMapper";
    private static final int INITIAL_EXPECTED_STATES = 1048576;

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        for (MethodNode method : input.methods) {
            if (!"<init>".equals(method.name) || !"()V".equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode initCall)
                        || initCall.getOpcode() != Opcodes.INVOKESPECIAL
                        || !ID_MAPPER_INTERNAL.equals(initCall.owner)
                        || !"<init>".equals(initCall.name)
                        || !"()V".equals(initCall.desc)) {
                    continue;
                }
                AbstractInsnNode prev = TransformAsmHelper.previousRealInsn(initCall);
                if (!(prev instanceof VarInsnNode loadThis)
                        || loadThis.getOpcode() != Opcodes.ALOAD
                        || loadThis.var != 0) {
                    continue;
                }

                method.instructions.insertBefore(initCall, new LdcInsnNode(INITIAL_EXPECTED_STATES));
                initCall.desc = "(I)V";
                method.maxStack = Math.max(method.maxStack, 2);
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
        return Set.of(Target.targetClass(TARGET));
    }
}

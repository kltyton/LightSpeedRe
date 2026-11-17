package com.ccr4ft3r.lightspeed.startup.bootstrap.transform;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Set;

/**
 * Presizes {@code Block.BLOCK_STATE_REGISTRY} ({@link net.minecraft.core.IdMapper}) in NeoForge 1.21.1
 * so table growth and rehashing do not stall registration across large modpacks.
 */
public final class BlockStateIdMapper121Transformer extends CompatClassTransformer {
    private static final String TARGET = "net.minecraft.world.level.block.Block";
    private static final String BLOCK_INTERNAL = "net/minecraft/world/level/block/Block";
    private static final String ID_MAPPER_INTERNAL = "net/minecraft/core/IdMapper";
    private static final int INITIAL_EXPECTED_STATES = 1048576;

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!BLOCK_INTERNAL.equals(input.name)) {
            return input;
        }
        for (MethodNode method : input.methods) {
            if (!"<clinit>".equals(method.name) || !"()V".equals(method.desc)) {
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
                AbstractInsnNode dupInsn = TransformAsmHelper.previousRealInsn(initCall);
                AbstractInsnNode newInsn = dupInsn == null ? null : TransformAsmHelper.previousRealInsn(dupInsn);
                AbstractInsnNode putStatic = TransformAsmHelper.nextRealInsn(initCall);
                if (dupInsn == null || dupInsn.getOpcode() != Opcodes.DUP
                        || !(newInsn instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == Opcodes.NEW
                        && ID_MAPPER_INTERNAL.equals(typeInsn.desc))
                        || !(putStatic instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && BLOCK_INTERNAL.equals(field.owner)
                        && "BLOCK_STATE_REGISTRY".equals(field.name))) {
                    continue;
                }
                method.instructions.insertBefore(initCall, new LdcInsnNode(INITIAL_EXPECTED_STATES));
                initCall.desc = "(I)V";
                method.maxStack = Math.max(method.maxStack, 4);
                return input;
            }
        }
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(TARGET);
    }
}

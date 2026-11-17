package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.loading;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.TransformAsmHelper;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Makes {@code ModLoader.loadingExceptions} / {@code loadingWarnings} thread-safe, exposes a bridge for
 * {@code ModLoader.buildMods(IModFile)}, and dispatches mod container building across FML's worker executor.
 */
public final class ModContainerPreparationTransformer implements ITransformer<ClassNode> {
    private static final String MOD_LOADER = "net/minecraftforge/fml/ModLoader";
    private static final String BRIDGE_INTERFACE = "com/ccr4ft3r/lightspeed/startup/loading/ModLoaderBridge";
    private static final String PREPARATION_HELPER = "com/ccr4ft3r/lightspeed/startup/loading/ModContainerPreparation";
    private static final String GATHER_DESC =
            "(Lnet/minecraftforge/fml/ModWorkManager$DrivenExecutor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V";
    private static final String BUILD_MODS_DESC =
            "(Lnet/minecraftforge/forgespi/locating/IModFile;)Ljava/util/List;";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!MOD_LOADER.equals(input.name)) {
            return input;
        }
        ensureBridge(input);
        for (MethodNode method : input.methods) {
            if ("<init>".equals(method.name) && "()V".equals(method.desc)) {
                synchronizeExceptionLists(method);
            } else if ("gatherAndInitializeMods".equals(method.name) && GATHER_DESC.equals(method.desc)) {
                parallelizeBuildMods(method);
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
        return Set.of(Target.targetClass("net.minecraftforge.fml.ModLoader"));
    }

    private static void ensureBridge(ClassNode classNode) {
        if (!classNode.interfaces.contains(BRIDGE_INTERFACE)) {
            classNode.interfaces.add(BRIDGE_INTERFACE);
        }
        for (MethodNode existing : classNode.methods) {
            if ("lightspeed$buildMods".equals(existing.name) && BUILD_MODS_DESC.equals(existing.desc)) {
                return;
            }
        }
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "lightspeed$buildMods",
                BUILD_MODS_DESC,
                null,
                null
        );
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                MOD_LOADER,
                "buildMods",
                BUILD_MODS_DESC,
                false
        ));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        bridge.maxStack = 2;
        bridge.maxLocals = 2;
        classNode.methods.add(bridge);
    }

    private static void synchronizeExceptionLists(MethodNode initMethod) {
        for (AbstractInsnNode insn = initMethod.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && MOD_LOADER.equals(field.owner)
                    && ("loadingExceptions".equals(field.name) || "loadingWarnings".equals(field.name))
                    && "Ljava/util/List;".equals(field.desc)) {
                initMethod.instructions.insertBefore(field, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/Collections",
                        "synchronizedList",
                        "(Ljava/util/List;)Ljava/util/List;",
                        false
                ));
            }
        }
    }

    private static void parallelizeBuildMods(MethodNode method) {
        Set<LabelNode> protectedLabels = TransformAsmHelper.collectControlFlowLabels(method);
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !MOD_LOADER.equals(field.owner)
                    || !"loadingModList".equals(field.name)
                    || !"Lnet/minecraftforge/fml/loading/LoadingModList;".equals(field.desc)) {
                continue;
            }
            AbstractInsnNode receiver = TransformAsmHelper.previousRealInsn(field);
            if (!(receiver instanceof VarInsnNode aload0)
                    || aload0.getOpcode() != Opcodes.ALOAD
                    || aload0.var != 0) {
                continue;
            }

            boolean sawGetModFiles = false;
            boolean sawBuildMods = false;
            MethodInsnNode toListCall = null;
            List<AbstractInsnNode> span = new ArrayList<>();
            span.add(aload0);
            for (AbstractInsnNode cur = aload0.getNext(); cur != null; cur = cur.getNext()) {
                if ((cur instanceof LabelNode label && protectedLabels.contains(label))
                        || cur instanceof JumpInsnNode
                        || cur instanceof FrameNode
                        || cur instanceof TableSwitchInsnNode
                        || cur instanceof LookupSwitchInsnNode) {
                    break;
                }
                span.add(cur);
                if (cur instanceof MethodInsnNode call) {
                    if ("net/minecraftforge/fml/loading/LoadingModList".equals(call.owner)
                            && "getModFiles".equals(call.name)) {
                        sawGetModFiles = true;
                    } else if (MOD_LOADER.equals(call.owner)
                            && "buildMods".equals(call.name)
                            && BUILD_MODS_DESC.equals(call.desc)) {
                        sawBuildMods = true;
                    } else if ("java/util/stream/Stream".equals(call.owner)
                            && "toList".equals(call.name)
                            && "()Ljava/util/List;".equals(call.desc)) {
                        toListCall = call;
                        break;
                    }
                } else if (cur instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle
                                && MOD_LOADER.equals(handle.getOwner())
                                && "buildMods".equals(handle.getName())
                                && BUILD_MODS_DESC.equals(handle.getDesc())) {
                            sawBuildMods = true;
                        }
                    }
                }
            }

            if (toListCall != null && sawGetModFiles && sawBuildMods) {
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
                replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
                replacement.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        MOD_LOADER,
                        "loadingModList",
                        "Lnet/minecraftforge/fml/loading/LoadingModList;"
                ));
                replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
                replacement.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PREPARATION_HELPER,
                        "buildAllMods",
                        "(Lnet/minecraftforge/fml/ModLoader;Lnet/minecraftforge/fml/loading/LoadingModList;Ljava/util/concurrent/Executor;)Ljava/util/List;",
                        false
                ));
                method.instructions.insertBefore(aload0, replacement);
                for (AbstractInsnNode node : span) {
                    if (node.getType() != AbstractInsnNode.LABEL && node.getType() != AbstractInsnNode.LINE) {
                        method.instructions.remove(node);
                    }
                }
                return;
            }
        }
    }
}

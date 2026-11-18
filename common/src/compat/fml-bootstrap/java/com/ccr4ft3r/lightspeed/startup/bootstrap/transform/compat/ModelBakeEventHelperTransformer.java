package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.TransformAsmHelper;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parallelizes ModernFix's {@code ModelBakeEventHelper.<init>} location table generation and replaces
 * {@code Sets.filter} in {@code ModelBakeEventHelper.wrapRegistry} with pre-bucketed namespace lookups.
 */
public final class ModelBakeEventHelperTransformer extends CompatClassTransformer {
    private static final String FORGE_PACKAGE = "org/embeddedt/modernfix/forge/dynresources";
    private static final String NEOFORGE_PACKAGE = "org/embeddedt/modernfix/neoforge/dynresources";
    private static final String SUPPORT = "com/ccr4ft3r/lightspeed/startup/client/ModelBakeHelperSupport";

    private final String targetDot;
    private final String targetInternal;
    private final String modelLocationBuilderInternal;

    public ModelBakeEventHelperTransformer() {
        this(FORGE_PACKAGE);
    }

    private ModelBakeEventHelperTransformer(String dynResourcesInternalPkg) {
        this.targetInternal = dynResourcesInternalPkg + "/ModelBakeEventHelper";
        this.targetDot = this.targetInternal.replace('/', '.');
        this.modelLocationBuilderInternal = dynResourcesInternalPkg + "/ModelLocationBuilder";
    }

    public static ModelBakeEventHelperTransformer forNeoForge() {
        return new ModelBakeEventHelperTransformer(NEOFORGE_PACKAGE);
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!targetInternal.equals(input.name)) {
            return input;
        }
        for (MethodNode method : input.methods) {
            if ("<init>".equals(method.name) && "(Ljava/util/Map;)V".equals(method.desc)) {
                patchTopLevelLocationSet(method);
                patchConstructor(method);
            } else if ("wrapRegistry".equals(method.name) && "(Ljava/lang/String;)Ljava/util/Map;".equals(method.desc)) {
                patchWrapRegistry(method);
            } else if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                patchThreadSafeWarnedModIds(method);
            }
        }
        return input;
    }

    private void patchTopLevelLocationSet(MethodNode ctor) {
        TypeInsnNode newInsn = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.NEW
                    && "it/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet".equals(typeInsn.desc)) {
                newInsn = typeInsn;
                break;
            }
        }
        if (newInsn == null) {
            return;
        }
        AbstractInsnNode dupInsn = TransformAsmHelper.nextRealInsn(newInsn);
        if (dupInsn == null || dupInsn.getOpcode() != Opcodes.DUP) {
            return;
        }
        MethodInsnNode initCall = null;
        for (AbstractInsnNode cur = dupInsn.getNext(); cur != null; cur = cur.getNext()) {
            if (cur instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == Opcodes.NEW) {
                return;
            }
            if (cur instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "it/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet".equals(call.owner)
                    && "<init>".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                initCall = call;
                break;
            }
        }
        if (initCall == null) {
            return;
        }
        AbstractInsnNode putField = TransformAsmHelper.nextRealInsn(initCall);
        if (!(putField instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.PUTFIELD
                || !targetInternal.equals(field.owner)
                || !"topLevelModelLocations".equals(field.name)) {
            return;
        }
        ctor.instructions.insertBefore(initCall, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT,
                "createTopLevelLocationSet",
                "(I)Ljava/util/Set;",
                false
        ));
        ctor.instructions.remove(newInsn);
        ctor.instructions.remove(dupInsn);
        ctor.instructions.remove(initCall);
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(targetDot);
    }

    private void patchConstructor(MethodNode ctor) {
        TypeInsnNode builderNew = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.NEW
                    && modelLocationBuilderInternal.equals(typeInsn.desc)) {
                builderNew = typeInsn;
                break;
            }
        }
        if (builderNew == null) {
            return;
        }

        AbstractInsnNode afterBlockEnumeration = null;
        String blockConsumer = "(L" + targetInternal + ";L" + modelLocationBuilderInternal
                + ";)Ljava/util/function/Consumer;";
        for (AbstractInsnNode cur = builderNew.getNext(); cur != null; cur = cur.getNext()) {
            if (cur instanceof MethodInsnNode call && "forEach".equals(call.name)) {
                AbstractInsnNode argument = TransformAsmHelper.previousRealInsn(call);
                if (call.getOpcode() == Opcodes.INVOKEINTERFACE && "java/util/Set".equals(call.owner)
                        && "(Ljava/util/function/Consumer;)V".equals(call.desc)
                        && argument instanceof InvokeDynamicInsnNode lambda
                        && "accept".equals(lambda.name) && blockConsumer.equals(lambda.desc)) {
                    afterBlockEnumeration = call.getNext();
                }
                break;
            }
        }
        if (afterBlockEnumeration == null) {
            return;
        }

        List<AbstractInsnNode> span = new ArrayList<>();
        Set<LabelNode> innerLabels = new HashSet<>();
        for (AbstractInsnNode cur = builderNew; cur != null && cur != afterBlockEnumeration; cur = cur.getNext()) {
            span.add(cur);
            if (cur != builderNew && cur instanceof LabelNode label) {
                innerLabels.add(label);
            }
            if (cur instanceof TableSwitchInsnNode || cur instanceof LookupSwitchInsnNode) {
                return;
            }
        }
        if (span.isEmpty() || hasExternalBranchOrTryCatch(ctor, span, innerLabels)) {
            return;
        }

        // Keep native item enumeration, model registry keys and resource discovery after this loop.
        LabelNode originalPath = new LabelNode();
        LabelNode afterPopulation = new LabelNode();
        InsnList replacement = new InsnList();
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT,
                "canPopulateLocations", "()Z", false));
        replacement.add(new JumpInsnNode(Opcodes.IFEQ, originalPath));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new FieldInsnNode(Opcodes.GETFIELD, targetInternal, "topLevelModelLocations", "Ljava/util/Set;"));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new FieldInsnNode(Opcodes.GETFIELD, targetInternal, "namespacesWithModels", "Ljava/util/Set;"));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, "populateBlockLocations",
                "(Ljava/util/Set;Ljava/util/Set;)V", false));
        replacement.add(new JumpInsnNode(Opcodes.GOTO, afterPopulation));
        replacement.add(originalPath);
        ctor.instructions.insertBefore(builderNew, replacement);
        ctor.instructions.insertBefore(afterBlockEnumeration, afterPopulation);
        if (ctor.maxStack < 4) {
            ctor.maxStack = 4;
        }
    }

    private static boolean hasExternalBranchOrTryCatch(
            MethodNode method,
            List<AbstractInsnNode> span,
            Set<LabelNode> innerLabels
    ) {
        if (innerLabels.isEmpty()) {
            return false;
        }
        Set<AbstractInsnNode> spanSet = new HashSet<>(span);
        for (AbstractInsnNode cur = method.instructions.getFirst(); cur != null; cur = cur.getNext()) {
            if (spanSet.contains(cur)) {
                continue;
            }
            if (cur instanceof JumpInsnNode jump && innerLabels.contains(jump.label)) {
                return true;
            }
            if (cur instanceof TableSwitchInsnNode ts) {
                if (innerLabels.contains(ts.dflt)) {
                    return true;
                }
                for (LabelNode label : ts.labels) {
                    if (innerLabels.contains(label)) {
                        return true;
                    }
                }
            } else if (cur instanceof LookupSwitchInsnNode ls) {
                if (innerLabels.contains(ls.dflt)) {
                    return true;
                }
                for (LabelNode label : ls.labels) {
                    if (innerLabels.contains(label)) {
                        return true;
                    }
                }
            }
        }
        if (method.tryCatchBlocks != null) {
            for (var tcb : method.tryCatchBlocks) {
                if (innerLabels.contains(tcb.start) || innerLabels.contains(tcb.end) || innerLabels.contains(tcb.handler)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void patchWrapRegistry(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"com/google/common/collect/Sets".equals(call.owner)
                    || !"filter".equals(call.name)
                    || !"(Ljava/util/Set;Lcom/google/common/base/Predicate;)Ljava/util/Set;".equals(call.desc)) {
                continue;
            }
            AbstractInsnNode prev = TransformAsmHelper.previousRealInsn(call);
            if (!(prev instanceof InvokeDynamicInsnNode indy)
                    || (!"test".equals(indy.name) && !"apply".equals(indy.name))
                    || !"(Ljava/util/Set;)Lcom/google/common/base/Predicate;".equals(indy.desc)) {
                continue;
            }
            InsnList replacement = new InsnList();
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    SUPPORT,
                    "wrapVisibleLocations",
                    "(Ljava/util/Set;Ljava/util/Set;)Ljava/util/Set;",
                    false
            ));
            method.instructions.insertBefore(call, replacement);
            method.instructions.remove(indy);
            method.instructions.remove(call);
            return;
        }
    }

    private void patchThreadSafeWarnedModIds(MethodNode clinit) {
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode put)
                    || put.getOpcode() != Opcodes.PUTSTATIC
                    || !targetInternal.equals(put.owner)
                    || !"WARNED_MOD_IDS".equals(put.name)
                    || !"Ljava/util/Set;".equals(put.desc)) {
                continue;
            }
            AbstractInsnNode initCall = TransformAsmHelper.previousRealInsn(put);
            if (!(initCall instanceof MethodInsnNode init)
                    || init.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"java/util/HashSet".equals(init.owner)
                    || !"<init>".equals(init.name)
                    || !"()V".equals(init.desc)) {
                return;
            }
            AbstractInsnNode dupNode = TransformAsmHelper.previousRealInsn(init);
            AbstractInsnNode newNode = dupNode != null ? TransformAsmHelper.previousRealInsn(dupNode) : null;
            if (dupNode == null || dupNode.getOpcode() != Opcodes.DUP
                    || !(newNode instanceof TypeInsnNode t)
                    || t.getOpcode() != Opcodes.NEW
                    || !"java/util/HashSet".equals(t.desc)) {
                return;
            }
            clinit.instructions.insertBefore(newNode, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/concurrent/ConcurrentHashMap",
                    "newKeySet",
                    "()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;",
                    false
            ));
            clinit.instructions.remove(newNode);
            clinit.instructions.remove(dupNode);
            clinit.instructions.remove(init);
            return;
        }
    }
}

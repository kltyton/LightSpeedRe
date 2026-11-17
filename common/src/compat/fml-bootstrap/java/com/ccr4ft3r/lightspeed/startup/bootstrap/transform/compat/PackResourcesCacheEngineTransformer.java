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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Eliminates PackResourcesCacheEngine.doGenerateCache() cross-thread stalls by populating single-JAR
 * packs from pre-indexed ZIP central directories and parallelizing multi-path scan tasks.
 */
public final class PackResourcesCacheEngineTransformer extends CompatClassTransformer {
    private static final String ENGINE = "org/embeddedt/modernfix/resources/PackResourcesCacheEngine";
    private static final String NODE = "org/embeddedt/modernfix/resources/PackResourcesCacheEngine$Node";
    private static final String SUPPORT = "com/ccr4ft3r/lightspeed/startup/compat/PackResourcesCacheSupport";
    private static final String FAST_GENERATE = "lightspeed$generateCache";
    private static final String POPULATE_ARRAY = "lightspeed$populateFromArray";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode doGenerate = findMethod(input, "doGenerateCache", "()V");
        if (doGenerate == null) {
            return input;
        }
        if (!patchDoGenerateCache(doGenerate)) {
            return input;
        }
        if (findMethod(input, POPULATE_ARRAY, "(Ljava/lang/String;[Ljava/lang/String;)V") == null) {
            input.methods.add(createPopulateFromArrayMethod());
        }
        if (findMethod(input, FAST_GENERATE, "()V") == null) {
            input.methods.add(createFastGenerateMethod());
        }
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of("org.embeddedt.modernfix.resources.PackResourcesCacheEngine");
    }

    private static MethodNode findMethod(ClassNode input, String name, String desc) {
        for (MethodNode method : input.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static boolean patchDoGenerateCache(MethodNode method) {
        FieldInsnNode tasksField = null;
        MethodInsnNode stopwatchStop = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (tasksField == null
                    && insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && ENGINE.equals(field.owner)
                    && "cacheGenerationTasks".equals(field.name)) {
                tasksField = field;
            } else if (tasksField != null
                    && insn instanceof MethodInsnNode call
                    && "com/google/common/base/Stopwatch".equals(call.owner)
                    && "stop".equals(call.name)) {
                stopwatchStop = call;
                break;
            }
        }
        if (tasksField == null || stopwatchStop == null) {
            return false;
        }

        AbstractInsnNode tasksOwnerLoad = TransformAsmHelper.previousRealInsn(tasksField);
        AbstractInsnNode stopReceiverLoad = TransformAsmHelper.previousRealInsn(stopwatchStop);
        if (!(tasksOwnerLoad instanceof VarInsnNode v0 && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0)
                || !(stopReceiverLoad instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD)) {
            return false;
        }

        List<AbstractInsnNode> span = new ArrayList<>();
        for (AbstractInsnNode cur = tasksOwnerLoad; cur != null && cur != stopReceiverLoad; cur = cur.getNext()) {
            span.add(cur);
        }
        if (span.isEmpty()) {
            return false;
        }

        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENGINE, FAST_GENERATE, "()V", false));
        method.instructions.insertBefore(tasksOwnerLoad, call);

        for (AbstractInsnNode node : span) {
            if (node.getType() != AbstractInsnNode.LABEL && node.getType() != AbstractInsnNode.LINE) {
                method.instructions.remove(node);
            }
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        return true;
    }

    private static MethodNode createFastGenerateMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, FAST_GENERATE, "()V", null, null);
        LabelNode fallback = new LabelNode();

        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, ENGINE, "rootPathsByType", "Ljava/util/Map;"));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT,
                "tryExtractSingleJarPack",
                "(Ljava/util/Map;)[[Ljava/lang/String;",
                false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, fallback));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new LdcInsnNode("assets"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                ENGINE,
                POPULATE_ARRAY,
                "(Ljava/lang/String;[Ljava/lang/String;)V",
                false
        ));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new LdcInsnNode("data"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                ENGINE,
                POPULATE_ARRAY,
                "(Ljava/lang/String;[Ljava/lang/String;)V",
                false
        ));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, ENGINE, "root", "L" + NODE + ";"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NODE, "optimize", "()V", false));
        insns.add(new InsnNode(Opcodes.RETURN));

        insns.add(fallback);
        insns.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[]{ENGINE, "[[Ljava/lang/String;"},
                0,
                new Object[0]
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, ENGINE, "cacheGenerationTasks", "Ljava/util/List;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, "runGenerationTasks", "(Ljava/util/List;)V", false));
        insns.add(new InsnNode(Opcodes.RETURN));

        method.maxStack = 4;
        method.maxLocals = 2;
        return method;
    }

    private static MethodNode createPopulateFromArrayMethod() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                POPULATE_ARRAY,
                "(Ljava/lang/String;[Ljava/lang/String;)V",
                null,
                null
        );

        LabelNode done = new LabelNode();
        LabelNode outerLoop = new LabelNode();
        LabelNode outerDone = new LabelNode();
        LabelNode segLoop = new LabelNode();
        LabelNode isLastBranch = new LabelNode();
        LabelNode putIntern = new LabelNode();
        LabelNode afterIntern = new LabelNode();
        LabelNode haveChildren = new LabelNode();
        LabelNode haveNext = new LabelNode();
        LabelNode segDone = new LabelNode();

        Object[] fullOuter = new Object[]{
                ENGINE,
                "java/lang/String",
                "[Ljava/lang/String;",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER
        };
        Object[] fullSeg = new Object[]{
                ENGINE,
                "java/lang/String",
                "[Ljava/lang/String;",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER
        };
        Object[] fullSlash = new Object[]{
                ENGINE,
                "java/lang/String",
                "[Ljava/lang/String;",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                Opcodes.INTEGER
        };
        Object[] fullPutIntern = new Object[]{
                ENGINE,
                "java/lang/String",
                "[Ljava/lang/String;",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String"
        };
        Object[] fullHaveChildren = new Object[]{
                ENGINE,
                "java/lang/String",
                "[Ljava/lang/String;",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                "java/util/Map"
        };
        Object[] fullHaveNext = new Object[]{
                ENGINE,
                "java/lang/String",
                "[Ljava/lang/String;",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                NODE,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                Opcodes.INTEGER,
                "java/lang/String",
                "java/util/Map",
                NODE
        };

        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, done));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, ENGINE, "root", "L" + NODE + ";"));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, NODE, "children", "Ljava/util/Map;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, NODE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, done));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 4));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 5));

        insns.add(outerLoop);
        insns.add(new FrameNode(Opcodes.F_FULL, fullOuter.length, fullOuter, 0, new Object[0]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 7));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 8));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 9));

        insns.add(segLoop);
        insns.add(new FrameNode(Opcodes.F_FULL, fullSeg.length, fullSeg, 0, new Object[0]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, segDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, '/'));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 10));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insns.add(new JumpInsnNode(Opcodes.IFLT, isLastBranch));

        // Directory segment: intern via PATH_COMPONENT_INTERNER without lambda allocation.
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, ENGINE, "PATH_COMPONENT_INTERNER", "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 12));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, putIntern));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterIntern));

        insns.add(putIntern);
        insns.add(new FrameNode(Opcodes.F_FULL, fullPutIntern.length, fullPutIntern, 0, new Object[0]));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, ENGINE, "PATH_COMPONENT_INTERNER", "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/ConcurrentHashMap",
                "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 12));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, afterIntern));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterIntern));

        // Leaf file segment: no interning needed (matches ModernFix lambda$new$3 behavior).
        insns.add(isLastBranch);
        insns.add(new FrameNode(Opcodes.F_FULL, fullSlash.length, fullSlash, 0, new Object[0]));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 11));

        insns.add(afterIntern);
        insns.add(new FrameNode(Opcodes.F_FULL, fullPutIntern.length, fullPutIntern, 0, new Object[0]));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, NODE, "children", "Ljava/util/Map;"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 12));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, haveChildren));
        insns.add(new TypeInsnNode(Opcodes.NEW, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap", "<init>", "()V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 12));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, NODE, "children", "Ljava/util/Map;"));

        insns.add(haveChildren);
        insns.add(new FrameNode(Opcodes.F_FULL, fullHaveChildren.length, fullHaveChildren, 0, new Object[0]));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, NODE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 13));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 13));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, haveNext));
        insns.add(new TypeInsnNode(Opcodes.NEW, NODE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, NODE, "<init>", "()V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 13));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 13));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(haveNext);
        insns.add(new FrameNode(Opcodes.F_FULL, fullHaveNext.length, fullHaveNext, 0, new Object[0]));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 13));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 7));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insns.add(new JumpInsnNode(Opcodes.IFLT, segDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 8));
        insns.add(new JumpInsnNode(Opcodes.GOTO, segLoop));

        insns.add(segDone);
        insns.add(new FrameNode(Opcodes.F_FULL, fullOuter.length, fullOuter, 0, new Object[0]));
        insns.add(new IincInsnNode(5, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, outerLoop));

        insns.add(outerDone);
        insns.add(new FrameNode(Opcodes.F_FULL, fullOuter.length, fullOuter, 0, new Object[0]));
        insns.add(new InsnNode(Opcodes.RETURN));

        insns.add(done);
        insns.add(new FrameNode(
                Opcodes.F_FULL,
                3,
                new Object[]{ENGINE, "java/lang/String", "[Ljava/lang/String;"},
                0,
                new Object[0]
        ));
        insns.add(new InsnNode(Opcodes.RETURN));

        method.maxStack = 4;
        method.maxLocals = 14;
        return method;
    }
}

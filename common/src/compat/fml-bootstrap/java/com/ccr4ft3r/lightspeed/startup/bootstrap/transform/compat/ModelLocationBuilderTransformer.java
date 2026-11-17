package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.compat;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Adds zero-property and single-property fast paths to ModernFix {@code ModelLocationBuilder.generateForBlock}
 * so the majority of blocks bypass {@code ArrayList}, {@code Lists.cartesianProduct}, and {@code StringBuilder}.
 */
public final class ModelLocationBuilderTransformer extends CompatClassTransformer {
    private static final String FORGE_PACKAGE = "org/embeddedt/modernfix/forge/dynresources";
    private static final String NEOFORGE_PACKAGE = "org/embeddedt/modernfix/neoforge/dynresources";
    private static final String BLOCK_INTERNAL = "net/minecraft/world/level/block/Block";
    private static final String STATE_DEF_INTERNAL = "net/minecraft/world/level/block/state/StateDefinition";
    private static final String RESOURCE_LOCATION_INTERNAL = "net/minecraft/resources/ResourceLocation";
    private static final String MODEL_RES_LOC_INTERNAL =
            "net/minecraft/client/resources/model/ModelResourceLocation";
    private static final String PROPERTY_INTERNAL = "net/minecraft/world/level/block/state/properties/Property";

    private final String targetDot;
    private final String targetInternal;
    private final String propertyDataInternal;
    private final String fixedGetStateDefMethod;
    private final String fixedGetPropsMethod;

    public ModelLocationBuilderTransformer() {
        this(FORGE_PACKAGE, null, null);
    }

    private ModelLocationBuilderTransformer(
            String dynResourcesInternalPkg,
            String fixedGetStateDefMethod,
            String fixedGetPropsMethod
    ) {
        this.targetInternal = dynResourcesInternalPkg + "/ModelLocationBuilder";
        this.targetDot = this.targetInternal.replace('/', '.');
        this.propertyDataInternal = this.targetInternal + "$PropertyData";
        this.fixedGetStateDefMethod = fixedGetStateDefMethod;
        this.fixedGetPropsMethod = fixedGetPropsMethod;
    }

    public static ModelLocationBuilderTransformer forNeoForge() {
        return new ModelLocationBuilderTransformer(NEOFORGE_PACKAGE, "getStateDefinition", "getProperties");
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        MethodNode method = findGenerateForBlock(input);
        if (method == null) {
            return input;
        }
        String getStateDefName = fixedGetStateDefMethod;
        String getPropsName = fixedGetPropsMethod;
        if (getStateDefName == null || getPropsName == null) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    if (getStateDefName == null
                            && BLOCK_INTERNAL.equals(call.owner)
                            && ("()L" + STATE_DEF_INTERNAL + ";").equals(call.desc)) {
                        getStateDefName = call.name;
                    } else if (getPropsName == null
                            && STATE_DEF_INTERNAL.equals(call.owner)
                            && "()Ljava/util/Collection;".equals(call.desc)) {
                        getPropsName = call.name;
                    }
                }
            }
        }
        if (getStateDefName == null || getPropsName == null) {
            return input;
        }
        if (!hasComputePropertyOptions(input)) {
            return input;
        }

        LabelNode notEmptyLabel = new LabelNode();
        LabelNode fallbackLabel = new LabelNode();
        LabelNode hasDataLabel = new LabelNode();
        LabelNode singleLoopLabel = new LabelNode();
        LabelNode singleDoneLabel = new LabelNode();

        String selfFrame = targetInternal;
        Object[] entryLocals = {
                selfFrame, "java/util/Set", BLOCK_INTERNAL, RESOURCE_LOCATION_INTERNAL
        };

        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BLOCK_INTERNAL,
                getStateDefName,
                "()L" + STATE_DEF_INTERNAL + ";",
                false
        ));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                STATE_DEF_INTERNAL,
                getPropsName,
                "()Ljava/util/Collection;",
                false
        ));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 4));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 4));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "size", "()I", true));
        fastPath.add(new VarInsnNode(Opcodes.ISTORE, 5));

        // propCount == 0 -> target.add(new ModelResourceLocation(blockId, "")); return;
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 5));
        fastPath.add(new JumpInsnNode(Opcodes.IFNE, notEmptyLabel));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new TypeInsnNode(Opcodes.NEW, MODEL_RES_LOC_INTERNAL));
        fastPath.add(new InsnNode(Opcodes.DUP));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastPath.add(new LdcInsnNode(""));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                MODEL_RES_LOC_INTERNAL,
                "<init>",
                "(L" + RESOURCE_LOCATION_INTERNAL + ";Ljava/lang/String;)V",
                false
        ));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "add",
                "(Ljava/lang/Object;)Z",
                true
        ));
        fastPath.add(new InsnNode(Opcodes.POP));
        fastPath.add(new InsnNode(Opcodes.RETURN));

        // propCount != 1 -> fallback to multi-property original body
        fastPath.add(notEmptyLabel);
        fastPath.add(new FrameNode(
                Opcodes.F_FULL,
                6,
                new Object[]{
                        entryLocals[0], entryLocals[1], entryLocals[2], entryLocals[3],
                        "java/util/Collection", Opcodes.INTEGER
                },
                0,
                null
        ));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 5));
        fastPath.add(new InsnNode(Opcodes.ICONST_1));
        fastPath.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fallbackLabel));

        // prop = props.iterator().next() -> local 6
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 4));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Collection",
                "iterator",
                "()Ljava/util/Iterator;",
                true
        ));
        fastPath.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        fastPath.add(new TypeInsnNode(Opcodes.CHECKCAST, PROPERTY_INTERNAL));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // data = this.propertyToOptionStrings.get(prop) -> local 7
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, targetInternal, "propertyToOptionStrings", "Ljava/util/Map;"));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 6));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true
        ));
        fastPath.add(new TypeInsnNode(Opcodes.CHECKCAST, propertyDataInternal));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 7));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 7));
        fastPath.add(new JumpInsnNode(Opcodes.IFNONNULL, hasDataLabel));

        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 6));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                targetInternal,
                "computePropertyOptions",
                "(L" + PROPERTY_INTERNAL + ";)L" + propertyDataInternal + ";",
                false
        ));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 7));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, targetInternal, "propertyToOptionStrings", "Ljava/util/Map;"));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 6));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 7));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true
        ));
        fastPath.add(new InsnNode(Opcodes.POP));

        fastPath.add(hasDataLabel);
        fastPath.add(new FrameNode(
                Opcodes.F_FULL,
                8,
                new Object[]{
                        entryLocals[0], entryLocals[1], entryLocals[2], entryLocals[3],
                        "java/util/Collection", Opcodes.INTEGER, PROPERTY_INTERNAL, propertyDataInternal
                },
                0,
                null
        ));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 7));
        fastPath.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                propertyDataInternal,
                "nameValuePairs",
                "Lcom/google/common/collect/ImmutableList;"
        ));
        fastPath.add(new VarInsnNode(Opcodes.ASTORE, 8));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 8));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/google/common/collect/ImmutableList",
                "size",
                "()I",
                false
        ));
        fastPath.add(new VarInsnNode(Opcodes.ISTORE, 9));
        fastPath.add(new InsnNode(Opcodes.ICONST_0));
        fastPath.add(new VarInsnNode(Opcodes.ISTORE, 10));

        fastPath.add(singleLoopLabel);
        fastPath.add(new FrameNode(
                Opcodes.F_FULL,
                11,
                new Object[]{
                        entryLocals[0], entryLocals[1], entryLocals[2], entryLocals[3],
                        "java/util/Collection", Opcodes.INTEGER, PROPERTY_INTERNAL, propertyDataInternal,
                        "com/google/common/collect/ImmutableList", Opcodes.INTEGER, Opcodes.INTEGER
                },
                0,
                null
        ));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 10));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 9));
        fastPath.add(new JumpInsnNode(Opcodes.IF_ICMPGE, singleDoneLabel));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new TypeInsnNode(Opcodes.NEW, MODEL_RES_LOC_INTERNAL));
        fastPath.add(new InsnNode(Opcodes.DUP));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 8));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, 10));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/google/common/collect/ImmutableList",
                "get",
                "(I)Ljava/lang/Object;",
                false
        ));
        fastPath.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                MODEL_RES_LOC_INTERNAL,
                "<init>",
                "(L" + RESOURCE_LOCATION_INTERNAL + ";Ljava/lang/String;)V",
                false
        ));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "add",
                "(Ljava/lang/Object;)Z",
                true
        ));
        fastPath.add(new InsnNode(Opcodes.POP));
        fastPath.add(new IincInsnNode(10, 1));
        fastPath.add(new JumpInsnNode(Opcodes.GOTO, singleLoopLabel));

        fastPath.add(singleDoneLabel);
        fastPath.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        fastPath.add(new InsnNode(Opcodes.RETURN));

        // propCount >= 2 -> delegate to ModelVariantGeneratorSupport.generateMultiProperty(locations, blockId, props)
        fastPath.add(fallbackLabel);
        fastPath.add(new FrameNode(
                Opcodes.F_FULL,
                5,
                new Object[]{
                        entryLocals[0], entryLocals[1], entryLocals[2], entryLocals[3],
                        "java/util/Collection"
                },
                0,
                null
        ));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 4));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/ccr4ft3r/lightspeed/startup/client/ModelVariantGeneratorSupport",
                "generateMultiProperty",
                "(Ljava/util/Set;L" + RESOURCE_LOCATION_INTERNAL + ";Ljava/util/Collection;)V",
                false
        ));
        fastPath.add(new InsnNode(Opcodes.RETURN));

        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.instructions.add(fastPath);
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = 6;
        method.maxLocals = 14;
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(targetDot);
    }

    private static MethodNode findGenerateForBlock(ClassNode input) {
        String desc = "(Ljava/util/Set;L" + BLOCK_INTERNAL + ";L" + RESOURCE_LOCATION_INTERNAL + ";)V";
        for (MethodNode method : input.methods) {
            if ("generateForBlock".equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private boolean hasComputePropertyOptions(ClassNode input) {
        String desc = "(L" + PROPERTY_INTERNAL + ";)L" + propertyDataInternal + ";";
        for (MethodNode method : input.methods) {
            if ("computePropertyOptions".equals(method.name) && desc.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }
}

package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.forge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Presizes {@code ForgeRegistry}'s internal Guava {@code HashBiMap} instances ({@code ids}, {@code names},
 * {@code keys}, {@code owners}) before {@code sync()} populates thousands of frozen entries, eliminating
 * repeated table expansions and rehashing during freeze. A names/keys map which has exposed a
 * live view must retain its identity, including views obtained from the sync clear callback.
 */
public final class ForgeRegistrySyncTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net.minecraftforge.registries.ForgeRegistry";
    private static final String TARGET_INTERNAL = "net/minecraftforge/registries/ForgeRegistry";
    private static final String BIMAP_DESC = "Lcom/google/common/collect/BiMap;";
    private static final String HASH_BIMAP_INTERNAL = "com/google/common/collect/HashBiMap";
    private static final String SYNC_DESC =
            "(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraftforge/registries/ForgeRegistry;)V";
    private static final String PRESIZE_NAME = "lightspeed$presizeForSync";
    private static final String PRESIZE_DESC = "(Lnet/minecraftforge/registries/ForgeRegistry;)V";
    private static final String NAMES_VIEW_FLAG = "lightspeed$namesViewExposed";
    private static final String KEYS_VIEW_FLAG = "lightspeed$keysViewExposed";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name) || hasPresizeMethod(input)) {
            return input;
        }
        MethodNode sync = findSyncMethod(input);
        MethodInsnNode clearCall = sync == null ? null : findOwnersClear(sync);
        MethodNode getKeys = findViewMethod(input, "getKeys", "()Ljava/util/Set;", "names", "keySet");
        MethodNode getValues = findViewMethod(input, "getValues", "()Ljava/util/Collection;", "names", "values");
        MethodNode getResourceKeys = findViewMethod(input, "getResourceKeys", "()Ljava/util/Set;", "keys", "keySet");
        MethodNode getEntries = findViewMethod(input, "getEntries", "()Ljava/util/Set;", "keys", "entrySet");
        if (clearCall == null || getKeys == null || getValues == null || getResourceKeys == null
                || getEntries == null || !hasBiMaps(input) || hasViewFlags(input)) {
            return input;
        }
        clearFinalOnBiMaps(input);
        input.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                NAMES_VIEW_FLAG, "Z", null, null));
        input.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                KEYS_VIEW_FLAG, "Z", null, null));
        markViewExposed(getKeys, NAMES_VIEW_FLAG);
        markViewExposed(getValues, NAMES_VIEW_FLAG);
        markViewExposed(getResourceKeys, KEYS_VIEW_FLAG);
        markViewExposed(getEntries, KEYS_VIEW_FLAG);
        injectPresizeCall(sync, clearCall);
        input.methods.add(createPresizeMethod());
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

    private static boolean hasPresizeMethod(ClassNode input) {
        for (MethodNode m : input.methods) {
            if (PRESIZE_NAME.equals(m.name) && PRESIZE_DESC.equals(m.desc)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findSyncMethod(ClassNode input) {
        for (MethodNode m : input.methods) {
            if ("sync".equals(m.name) && SYNC_DESC.equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static boolean hasBiMaps(ClassNode input) {
        int matched = 0;
        for (FieldNode f : input.fields) {
            if (BIMAP_DESC.equals(f.desc)
                    && ("ids".equals(f.name) || "names".equals(f.name)
                    || "keys".equals(f.name) || "owners".equals(f.name))) {
                matched++;
            }
        }
        return matched == 4;
    }

    private static void clearFinalOnBiMaps(ClassNode input) {
        for (FieldNode f : input.fields) {
            if (BIMAP_DESC.equals(f.desc)
                    && ("ids".equals(f.name) || "names".equals(f.name)
                    || "keys".equals(f.name) || "owners".equals(f.name))) {
                f.access &= ~Opcodes.ACC_FINAL;
            }
        }
    }

    private static boolean hasViewFlags(ClassNode input) {
        for (FieldNode field : input.fields) {
            if (NAMES_VIEW_FLAG.equals(field.name) || KEYS_VIEW_FLAG.equals(field.name)) return true;
        }
        return false;
    }

    private static MethodNode findViewMethod(ClassNode input, String name, String desc,
                                             String fieldName, String operation) {
        for (MethodNode method : input.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)
                    || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            boolean fieldSeen = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && TARGET_INTERNAL.equals(field.owner) && fieldName.equals(field.name)
                        && BIMAP_DESC.equals(field.desc)) {
                    fieldSeen = true;
                } else if (fieldSeen && insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "com/google/common/collect/BiMap".equals(call.owner)
                        && operation.equals(call.name)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static void markViewExposed(MethodNode method, String flag) {
        InsnList mark = new InsnList();
        mark.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mark.add(new InsnNode(Opcodes.ICONST_1));
        mark.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, flag, "Z"));
        method.instructions.insert(mark);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static MethodInsnNode findOwnersClear(MethodNode sync) {
        boolean sawOwnersField = false;
        for (AbstractInsnNode insn = sync.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && TARGET_INTERNAL.equals(field.owner)
                    && "owners".equals(field.name)
                    && BIMAP_DESC.equals(field.desc)) {
                sawOwnersField = true;
            } else if (sawOwnersField
                    && insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && "com/google/common/collect/BiMap".equals(call.owner)
                    && "clear".equals(call.name)
                    && "()V".equals(call.desc)) {
                return call;
            } else if (sawOwnersField && !(insn instanceof LabelNode)) {
                sawOwnersField = false;
            }
        }
        return null;
    }

    private static void injectPresizeCall(MethodNode sync, MethodInsnNode clearCall) {
        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new VarInsnNode(Opcodes.ALOAD, 2));
        inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET_INTERNAL, PRESIZE_NAME, PRESIZE_DESC, false));
        sync.instructions.insert(clearCall, inject);
        sync.maxStack = Math.max(sync.maxStack, 4);
    }

    private static MethodNode createPresizeMethod() {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, PRESIZE_NAME, PRESIZE_DESC, null, null);
        LabelNode skip = new LabelNode();
        LabelNode allocOwners = new LabelNode();
        LabelNode skipNames = new LabelNode();
        LabelNode skipKeys = new LabelNode();

        InsnList insns = m.instructions;
        // int size = source.names.size();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, "names", BIMAP_DESC));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/google/common/collect/BiMap", "size", "()I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));

        // if (size <= 32) return;
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 32));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, skip));

        // this.ids = HashBiMap.create(size);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HASH_BIMAP_INTERNAL, "create", "(I)Lcom/google/common/collect/HashBiMap;", false));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, "ids", BIMAP_DESC));

        // Existing unmodifiable views retain the original backing map.
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, NAMES_VIEW_FLAG, "Z"));
        insns.add(new JumpInsnNode(Opcodes.IFNE, skipNames));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HASH_BIMAP_INTERNAL, "create", "(I)Lcom/google/common/collect/HashBiMap;", false));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, "names", BIMAP_DESC));
        insns.add(skipNames);

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, KEYS_VIEW_FLAG, "Z"));
        insns.add(new JumpInsnNode(Opcodes.IFNE, skipKeys));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HASH_BIMAP_INTERNAL, "create", "(I)Lcom/google/common/collect/HashBiMap;", false));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, "keys", BIMAP_DESC));
        insns.add(skipKeys);

        // int ownerSize = Math.max(16, source.owners.size());
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, "owners", BIMAP_DESC));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/google/common/collect/BiMap", "size", "()I", true));
        insns.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 16));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3));

        insns.add(allocOwners);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HASH_BIMAP_INTERNAL, "create", "(I)Lcom/google/common/collect/HashBiMap;", false));
        insns.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, "owners", BIMAP_DESC));

        insns.add(skip);
        insns.add(new InsnNode(Opcodes.RETURN));

        m.maxStack = 3;
        m.maxLocals = 4;
        return m;
    }
}

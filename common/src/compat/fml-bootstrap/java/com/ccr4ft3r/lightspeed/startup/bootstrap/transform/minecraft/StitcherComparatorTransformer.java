package com.ccr4ft3r.lightspeed.startup.bootstrap.transform.minecraft;

import com.ccr4ft3r.lightspeed.startup.bootstrap.transform.CompatClassTransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Replaces Minecraft's boxing {@code Comparator.comparing(...).thenComparing(...)} in
 * {@code Stitcher.<clinit>} with a primitive-field comparator preserving exact sort order
 * ({@code (-width, -height, entry.name())}) with zero allocations per comparison.
 */
public final class StitcherComparatorTransformer extends CompatClassTransformer {
    private static final String TARGET = "net.minecraft.client.renderer.texture.Stitcher";
    private static final String TARGET_INTERNAL = "net/minecraft/client/renderer/texture/Stitcher";
    private static final String HOLDER_INTERNAL = "net/minecraft/client/renderer/texture/Stitcher$Holder";
    private static final String COMPARE_METHOD = "lightspeed$compareHolders";
    private static final String COMPARE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)I";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!TARGET_INTERNAL.equals(input.name)) {
            return input;
        }
        MethodNode clinit = findClinit(input);
        if (clinit == null) {
            return input;
        }
        List<Handle> extractorHandles = collectExtractorHandles(clinit);
        FieldInsnNode comparatorField = findComparatorField(clinit);
        if (extractorHandles.size() != 3 || comparatorField == null) {
            return input;
        }
        FieldInsnNode primaryIntField = findHolderField(input, extractorHandles.get(0));
        FieldInsnNode secondaryIntField = findHolderField(input, extractorHandles.get(1));
        MethodNode nameMethodNode = findMethod(input, extractorHandles.get(2));
        if (primaryIntField == null || secondaryIntField == null || nameMethodNode == null) {
            return input;
        }
        FieldInsnNode entryField = findHolderEntryField(nameMethodNode);
        MethodInsnNode entryNameCall = findEntryNameCall(nameMethodNode);
        if (entryField == null || entryNameCall == null) {
            return input;
        }
        ensureCompareMethod(input, primaryIntField, secondaryIntField, entryField, entryNameCall);
        rewriteClinit(clinit, comparatorField);
        return input;
    }

    @Override
    protected Set<String> targetClassNames() {
        return Set.of(TARGET);
    }

    private static MethodNode findClinit(ClassNode input) {
        for (MethodNode m : input.methods) {
            if ("<clinit>".equals(m.name) && "()V".equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static List<Handle> collectExtractorHandles(MethodNode clinit) {
        List<Handle> result = new ArrayList<>(3);
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy
                    && "apply".equals(indy.name)
                    && indy.bsmArgs != null
                    && indy.bsmArgs.length >= 2
                    && indy.bsmArgs[1] instanceof Handle target
                    && TARGET_INTERNAL.equals(target.getOwner())) {
                result.add(target);
            }
        }
        return result;
    }

    private static FieldInsnNode findComparatorField(MethodNode clinit) {
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode field
                    && TARGET_INTERNAL.equals(field.owner)
                    && "Ljava/util/Comparator;".equals(field.desc)) {
                return field;
            }
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode input, Handle handle) {
        for (MethodNode m : input.methods) {
            if (handle.getName().equals(m.name) && handle.getDesc().equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static FieldInsnNode findHolderField(ClassNode input, Handle handle) {
        MethodNode method = findMethod(input, handle);
        if (method == null) {
            return null;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode field
                    && HOLDER_INTERNAL.equals(field.owner)
                    && "I".equals(field.desc)) {
                return field;
            }
        }
        return null;
    }

    private static FieldInsnNode findHolderEntryField(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode field
                    && HOLDER_INTERNAL.equals(field.owner)) {
                return field;
            }
        }
        return null;
    }

    private static MethodInsnNode findEntryNameCall(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.INVOKEINTERFACE && insn instanceof MethodInsnNode call
                    && call.desc.endsWith("Lnet/minecraft/resources/ResourceLocation;")) {
                return call;
            }
        }
        return null;
    }

    private static void ensureCompareMethod(
            ClassNode input,
            FieldInsnNode primaryIntField,
            FieldInsnNode secondaryIntField,
            FieldInsnNode entryField,
            MethodInsnNode entryNameCall
    ) {
        for (MethodNode m : input.methods) {
            if (COMPARE_METHOD.equals(m.name) && COMPARE_DESC.equals(m.desc)) {
                return;
            }
        }
        MethodNode m = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                COMPARE_METHOD,
                COMPARE_DESC,
                null,
                null
        );
        InsnList insns = m.instructions;
        LabelNode secondCheck = new LabelNode(new Label());
        LabelNode thirdCheck = new LabelNode(new Label());

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, HOLDER_INTERNAL));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, HOLDER_INTERNAL));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // Integer.compare(b.width, a.width) matches Integer.compare(-a.width, -b.width)
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_INTERNAL, primaryIntField.name, primaryIntField.desc));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_INTERNAL, primaryIntField.name, primaryIntField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "compare", "(II)I", false));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, secondCheck));
        insns.add(new InsnNode(Opcodes.IRETURN));

        insns.add(secondCheck);
        insns.add(new FrameNode(Opcodes.F_APPEND, 2, new Object[]{HOLDER_INTERNAL, HOLDER_INTERNAL}, 1, new Object[]{Opcodes.INTEGER}));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_INTERNAL, secondaryIntField.name, secondaryIntField.desc));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_INTERNAL, secondaryIntField.name, secondaryIntField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "compare", "(II)I", false));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, thirdCheck));
        insns.add(new InsnNode(Opcodes.IRETURN));

        insns.add(thirdCheck);
        insns.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER}));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_INTERNAL, entryField.name, entryField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, entryNameCall.owner, entryNameCall.name, entryNameCall.desc, true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_INTERNAL, entryField.name, entryField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, entryNameCall.owner, entryNameCall.name, entryNameCall.desc, true));
        insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/resources/ResourceLocation",
                "compareTo",
                "(Lnet/minecraft/resources/ResourceLocation;)I",
                false
        ));
        insns.add(new InsnNode(Opcodes.IRETURN));

        m.maxStack = 2;
        m.maxLocals = 4;
        input.methods.add(m);
    }

    private static void rewriteClinit(MethodNode clinit, FieldInsnNode comparatorField) {
        InsnList rewritten = new InsnList();
        Handle metafactory = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
        );
        Handle compareHandle = new Handle(
                Opcodes.H_INVOKESTATIC,
                TARGET_INTERNAL,
                COMPARE_METHOD,
                COMPARE_DESC,
                false
        );
        // Keep the original expression and all other static initialization intact.
        // Replacing only its resulting value preserves any preceding side effects,
        // exception handlers, labels and stack-map frames.
        rewritten.add(new InsnNode(Opcodes.POP));
        rewritten.add(new InvokeDynamicInsnNode(
                "compare",
                "()Ljava/util/Comparator;",
                metafactory,
                Type.getMethodType(COMPARE_DESC),
                compareHandle,
                Type.getMethodType(COMPARE_DESC)
        ));
        clinit.instructions.insertBefore(comparatorField, rewritten);
        clinit.maxStack = Math.max(clinit.maxStack, 1);
    }
}

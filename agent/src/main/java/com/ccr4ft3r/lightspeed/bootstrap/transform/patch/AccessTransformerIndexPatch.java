package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public final class AccessTransformerIndexPatch {
    private static final String INDEX = "lightspeed$targetIndex";
    private static final String BUILD = "lightspeed$buildTargetIndex";
    private static final String MAP = "Ljava/util/Map;";
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    private AccessTransformerIndexPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode contains = node.methods.stream().filter(m -> m.name.equals("containsClassTarget")).findFirst().orElseThrow();
        MethodNode select = node.methods.stream().filter(m -> m.name.equals("getTransformersForTarget")).findFirst().orElseThrow();
        String asmType = Type.getArgumentTypes(contains.desc)[0].getDescriptor();
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, INDEX, MAP, null, null));
        node.methods.add(builder(node.name, asmType));

        contains.instructions.clear(); contains.tryCatchBlocks.clear();
        if (contains.localVariables != null) contains.localVariables.clear();
        contains.instructions.add(index(node.name));
        contains.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        contains.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true));
        contains.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodInsnNode map = AsmPatchSupport.uniqueInvocation(select, Opcodes.INVOKEINTERFACE,
                "java/util/stream/Stream", "map", "(Ljava/util/function/Function;)Ljava/util/stream/Stream;");
        AbstractInsnNode collector = map.getNext();
        while (select.instructions.getFirst() != collector) select.instructions.remove(select.instructions.getFirst());
        if (select.localVariables != null) select.localVariables.clear();
        InsnList selected = index(node.name);
        selected.add(new VarInsnNode(Opcodes.ALOAD, 1));
        selected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
        selected.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        selected.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        selected.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "stream", "()Ljava/util/stream/Stream;", true));
        select.instructions.insert(selected);

        MethodNode load = node.methods.stream().filter(m -> m.name.equals("loadFromPath")).findFirst().orElseThrow();
        MethodInsnNode commit = AsmPatchSupport.uniqueInvocation(load, Opcodes.INVOKEINTERFACE,
                "java/util/Map", "putAll", "(Ljava/util/Map;)V");
        InsnList invalidate = new InsnList();
        invalidate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invalidate.add(new InsnNode(Opcodes.ACONST_NULL));
        invalidate.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, INDEX, MAP));
        load.instructions.insert(commit, invalidate);
        return AsmPatchSupport.write(node);
    }

    private static InsnList index(String owner) {
        LabelNode ready = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, INDEX, MAP));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, BUILD, "()" + MAP, false));
        code.add(ready);
        code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/util/Map"}));
        return code;
    }

    private static MethodNode builder(String owner, String asmType) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_SYNTHETIC,
                BUILD, "()" + MAP, null, null);
        LabelNode ready = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, INDEX, MAP));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "accessTransformers", MAP));
        method.instructions.add(new InvokeDynamicInsnNode("apply", "()Ljava/util/function/Function;", METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, "net/minecraftforge/accesstransformer/Target", "getASMType", "()" + asmType, false),
                Type.getMethodType("(Lnet/minecraftforge/accesstransformer/Target;)" + asmType)));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AsmPatchSupport.HOOK_OWNER,
                "indexAccessTransformers", "(Ljava/util/Map;Ljava/util/function/Function;)Ljava/util/Map;", false));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, INDEX, MAP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, INDEX, MAP));
        method.instructions.add(ready);
        method.instructions.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/util/Map"}));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }
}

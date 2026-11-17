package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;

public final class ModConstructionPatch {
    private static final String INFO = "Lnet/minecraftforge/forgespi/language/IModInfo;";
    private static final String SCAN = "Lnet/minecraftforge/forgespi/language/ModFileScanData;";
    private static final String ARGUMENTS = "(" + INFO + "Ljava/lang/String;" + SCAN + "Ljava/lang/ModuleLayer;)V";

    private ModConstructionPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode constructor = AsmPatchSupport.method(node, "<init>", ARGUMENTS);
        MethodNode construct = AsmPatchSupport.method(node, "constructMod", "()V");
        if (constructor.tryCatchBlocks.size() != 1) throw new IllegalStateException("unexpected class-load boundary");
        TryCatchBlockNode loadBoundary = constructor.tryCatchBlocks.get(0);
        AsmPatchSupport.uniqueInvocation(constructor, Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;");

        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "lightspeed$className", "Ljava/lang/String;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "lightspeed$gameLayer", "Ljava/lang/ModuleLayer;", null, null));
        node.fields.stream().filter(field -> field.name.equals("modClass"))
                .forEach(field -> field.access &= ~Opcodes.ACC_FINAL);

        // Keep the original module lookup, logging and error conversion together.
        // CONSTRUCT already runs on Forge workers; container creation stays ordered.
        MethodNode load = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "lightspeed$loadModClass", ARGUMENTS, null, null);
        AbstractInsnNode instruction = loadBoundary.start;
        while (instruction != null) {
            AbstractInsnNode next = instruction.getNext();
            constructor.instructions.remove(instruction);
            load.instructions.add(instruction);
            instruction = next;
        }
        load.tryCatchBlocks = new ArrayList<>(constructor.tryCatchBlocks);
        load.maxLocals = constructor.maxLocals;
        load.maxStack = constructor.maxStack;
        constructor.tryCatchBlocks.clear();
        constructor.localVariables = null;
        node.methods.add(load);

        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name,
                "lightspeed$className", "Ljava/lang/String;"));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name,
                "lightspeed$gameLayer", "Ljava/lang/ModuleLayer;"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));

        InsnList prepare = new InsnList();
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getModInfo", "()" + INFO, false));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "lightspeed$className", "Ljava/lang/String;"));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "scanResults", SCAN));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "lightspeed$gameLayer", "Ljava/lang/ModuleLayer;"));
        prepare.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, load.name, ARGUMENTS, false));
        construct.instructions.insert(prepare);
        selectConstructorWithoutExpectedException(node, construct);
        return AsmPatchSupport.write(node);
    }

    private static void selectConstructorWithoutExpectedException(ClassNode node, MethodNode construct) {
        MethodInsnNode lookup = null;
        for (AbstractInsnNode instruction : construct.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals("java/lang/Class")
                    || !call.name.equals("getDeclaredConstructor")
                    || !call.desc.equals("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;")) continue;
            AbstractInsnNode previous = call.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (previous == null || previous.getOpcode() != Opcodes.AASTORE) continue;
            if (lookup != null) throw new IllegalStateException("Multiple context constructor lookups");
            lookup = call;
        }
        // Older FML versions only perform the no-argument lookup.
        if (lookup == null) return;

        MethodNode select = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lightspeed$findModConstructor", "(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                null, new String[]{"java/lang/NoSuchMethodException"});
        InsnList code = select.instructions;
        LabelNode next = new LabelNode();
        LabelNode different = new LabelNode();
        LabelNode advance = new LabelNode();
        LabelNode exhausted = new LabelNode();
        LabelNode missing = new LabelNode();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        // Keep reflection in FMLModContainer, including its caller-sensitive access check.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructors",
                "()[Ljava/lang/reflect/Constructor;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(next);
        code.add(new FrameNode(Opcodes.F_FULL, 5, new Object[]{"java/lang/Class", "[Ljava/lang/Class;",
                "java/lang/reflect/Constructor", "[Ljava/lang/reflect/Constructor;", Opcodes.INTEGER}, 0, null));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, exhausted));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Constructor", "getParameterTypes",
                "()[Ljava/lang/Class;", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "equals",
                "([Ljava/lang/Object;[Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, different));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(different);
        code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/reflect/Constructor"}, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, advance));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Constructor", "getParameterCount", "()I", false));
        code.add(new JumpInsnNode(Opcodes.IFNE, advance));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(advance);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new IincInsnNode(4, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, next));
        code.add(exhausted);
        code.add(new FrameNode(Opcodes.F_CHOP, 1, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IFNULL, missing));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(missing);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        select.maxLocals = 6;
        node.methods.add(select);
        lookup.setOpcode(Opcodes.INVOKESTATIC);
        lookup.owner = node.name;
        lookup.name = select.name;
        lookup.desc = select.desc;
        lookup.itf = false;
    }
}

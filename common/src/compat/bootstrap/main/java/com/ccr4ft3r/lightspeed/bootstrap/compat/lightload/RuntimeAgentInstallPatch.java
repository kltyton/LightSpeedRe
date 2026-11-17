package com.ccr4ft3r.lightspeed.bootstrap.compat.lightload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Keeps the original agent options and status handling while replacing the helper JVM transport. */
public final class RuntimeAgentInstallPatch {
    private static final String HOOKS = "com/ccr4ft3r/lightspeed/bootstrap/runtime/BootstrapHooks";
    private static final String PATH = "java/nio/file/Path";

    private RuntimeAgentInstallPatch() { }

    public static byte[] apply(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode method = node.methods.stream()
                .filter(value -> value.name.equals("install") && value.desc.equals("(Ljava/lang/String;)V"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing agent installation method"));
        AbstractInsnNode process = null;
        AbstractInsnNode failure = null;
        LabelNode success = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals("loadNativeAgent")) return bytes;
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/lang/ProcessBuilder")) {
                if (process != null) throw new IllegalStateException("Multiple helper JVM launches");
                process = type;
            }
            if (instruction instanceof LdcInsnNode constant && constant.cst.equals("attach-failed")) {
                if (failure != null) throw new IllegalStateException("Multiple attach failure branches");
                failure = previousExecutable(instruction);
                if (!(failure instanceof LdcInsnNode property)
                        || !property.cst.equals("lightload.runtimeAgent.status")) {
                    throw new IllegalStateException("Unexpected attach failure status");
                }
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals("java/lang/Process")
                    && call.name.equals("exitValue") && call.desc.equals("()I")) {
                if (success != null) throw new IllegalStateException("Multiple helper exit checks");
                AbstractInsnNode next = nextExecutable(instruction);
                if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) {
                    throw new IllegalStateException("Unexpected helper exit check");
                }
                success = jump.label;
            }
        }
        if (process == null || failure == null || success == null) {
            throw new IllegalStateException("Incomplete helper JVM installation contract");
        }

        // These locals belong only to the original process transport and are dead in the common status tail.
        boolean statusTail = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == failure) statusTail = true;
            if (statusTail && instruction instanceof FrameNode frame && frame.local.size() > 6) {
                frame.local.set(5, Opcodes.TOP);
                frame.local.set(6, Opcodes.TOP);
            }
        }
        Object[] arguments = {"java/lang/String", PATH, PATH, PATH, "java/lang/String"};
        LabelNode nativeFailure = new LabelNode();
        InsnList failureFrame = new InsnList();
        failureFrame.add(nativeFailure);
        Object[] statusLocals = Arrays.copyOf(arguments, 8);
        statusLocals[5] = Opcodes.TOP;
        statusLocals[6] = Opcodes.TOP;
        statusLocals[7] = "java/lang/String";
        failureFrame.add(new FrameNode(Opcodes.F_NEW, statusLocals.length, statusLocals, 0, null));
        method.instructions.insertBefore(failure, failureFrame);

        int status = method.maxLocals++;
        LabelNode original = new LabelNode();
        LabelNode loaded = new LabelNode();
        InsnList install = new InsnList();
        install.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "hasNativeAgentLoader", "()Z", false));
        install.add(new JumpInsnNode(Opcodes.IFEQ, original));
        install.add(new VarInsnNode(Opcodes.ALOAD, 2));
        install.add(new VarInsnNode(Opcodes.ALOAD, 4));
        install.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "loadNativeAgent",
                "(Ljava/nio/file/Path;Ljava/lang/String;)I", false));
        install.add(new VarInsnNode(Opcodes.ISTORE, status));
        install.add(new VarInsnNode(Opcodes.ILOAD, status));
        install.add(new JumpInsnNode(Opcodes.IFEQ, loaded));
        install.add(new LdcInsnNode("Agent_OnAttach returned status "));
        install.add(new VarInsnNode(Opcodes.ILOAD, status));
        install.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false));
        install.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        install.add(new VarInsnNode(Opcodes.ASTORE, 7));
        install.add(new JumpInsnNode(Opcodes.GOTO, nativeFailure));
        install.add(loaded);
        List<Object> loadedLocals = new ArrayList<>(List.of(arguments));
        while (loadedLocals.size() < status) loadedLocals.add(Opcodes.TOP);
        loadedLocals.add(Opcodes.INTEGER);
        install.add(new FrameNode(Opcodes.F_NEW, loadedLocals.size(), loadedLocals.toArray(), 0, null));
        install.add(new LdcInsnNode(""));
        install.add(new VarInsnNode(Opcodes.ASTORE, 7));
        install.add(new JumpInsnNode(Opcodes.GOTO, success));
        install.add(original);
        install.add(new FrameNode(Opcodes.F_NEW, arguments.length, arguments, 0, null));
        method.instructions.insertBefore(process, install);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}

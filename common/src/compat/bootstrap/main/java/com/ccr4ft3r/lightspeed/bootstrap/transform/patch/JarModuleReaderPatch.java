package com.ccr4ft3r.lightspeed.bootstrap.transform.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class JarModuleReaderPatch {
    private static final Map<ClassLoader, byte[]> JARS = Collections.synchronizedMap(new WeakHashMap<>());

    private JarModuleReaderPatch() { }

    public static void capture(ClassLoader loader, byte[] bytes) {
        JARS.put(loader, bytes.clone());
    }

    public static byte[] apply(byte[] bytes, ClassLoader loader) {
        byte[] jarBytes = JARS.get(loader);
        if (jarBytes == null) throw new IllegalStateException("SecureJar definition was not captured before its reader");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode open = AsmPatchSupport.method(node, "open", "(Ljava/lang/String;)Ljava/util/Optional;");
        if (open.maxLocals != 2) throw new IllegalStateException("Unexpected module reader locals");
        MethodInsnNode findFile = AsmPatchSupport.uniqueInvocation(open, Opcodes.INVOKEVIRTUAL,
                "cpw/mods/jarhandling/impl/Jar", "findFile", "(Ljava/lang/String;)Ljava/util/Optional;");
        AbstractInsnNode mapper = findFile.getNext();
        while (mapper.getOpcode() < 0) mapper = mapper.getNext();
        if (!(mapper instanceof InvokeDynamicInsnNode)) {
            throw new IllegalStateException("Unexpected module URI mapper");
        }
        AbstractInsnNode convert = mapper.getNext();
        while (convert.getOpcode() < 0) convert = convert.getNext();
        if (!(convert instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !call.owner.equals("java/util/Optional") || !call.name.equals("map")
                || !call.desc.equals("(Ljava/util/function/Function;)Ljava/util/Optional;")) {
            throw new IllegalStateException("Unexpected module URI conversion");
        }

        // Inline the running SecureJar version's lookup, including its overlay and filter rules.
        MethodNode lookup = ResourceLookupPatch.pathLookup(jarBytes);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode instruction : lookup.instructions) {
            if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        }
        LabelNode result = new LabelNode();
        InsnList inline = new InsnList();
        inline.add(new VarInsnNode(Opcodes.ASTORE, 3));
        inline.add(new VarInsnNode(Opcodes.ASTORE, 2));
        for (AbstractInsnNode instruction : lookup.instructions) {
            AbstractInsnNode copy = instruction.clone(labels);
            if (copy instanceof VarInsnNode variable) variable.var += 2;
            if (copy instanceof IincInsnNode increment) increment.var += 2;
            if (copy instanceof FrameNode frame) {
                frame.local.add(0, "java/lang/String");
                frame.local.add(0, node.name);
            }
            if (copy.getOpcode() == Opcodes.ARETURN) {
                copy = new JumpInsnNode(Opcodes.GOTO, result);
            }
            inline.add(copy);
        }
        inline.add(result);
        inline.add(new FrameNode(Opcodes.F_NEW, 2, new Object[]{node.name, "java/lang/String"},
                1, new Object[]{"java/util/Optional"}));
        for (TryCatchBlockNode block : lookup.tryCatchBlocks) {
            open.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end),
                    labels.get(block.handler), block.type));
        }
        open.instructions.insertBefore(findFile, inline);
        open.instructions.remove(findFile);
        open.instructions.remove(mapper);
        open.instructions.remove(convert);
        open.maxLocals = Math.max(open.maxLocals, lookup.maxLocals + 2);
        return AsmPatchSupport.write(node);
    }

    /**
     * Patches SecureJar 3.0.x {@code Jar$JarModuleDataProvider.open(String)} to call
     * {@code JarContentsImpl.lightspeed$findPath(String)} directly, bypassing {@code Path -> URI -> Path}.
     */
    public static byte[] applyV3(byte[] bytes) {
        ClassNode node = AsmPatchSupport.read(bytes);
        MethodNode open = AsmPatchSupport.method(node, "open", "(Ljava/lang/String;)Ljava/util/Optional;");
        MethodInsnNode findFile = AsmPatchSupport.uniqueInvocation(open, Opcodes.INVOKEVIRTUAL,
                "cpw/mods/jarhandling/impl/Jar", "findFile", "(Ljava/lang/String;)Ljava/util/Optional;");
        AbstractInsnNode loadName = findFile.getPrevious();
        while (loadName != null && loadName.getOpcode() < 0) loadName = loadName.getPrevious();
        if (!(loadName instanceof VarInsnNode varLoad) || varLoad.getOpcode() != Opcodes.ALOAD || varLoad.var != 1) {
            throw new IllegalStateException("Unexpected SecureJar 3.x module reader name load");
        }
        AbstractInsnNode mapper = findFile.getNext();
        while (mapper != null && mapper.getOpcode() < 0) mapper = mapper.getNext();
        if (!(mapper instanceof InvokeDynamicInsnNode)) {
            throw new IllegalStateException("Unexpected SecureJar 3.x module URI mapper");
        }
        AbstractInsnNode convert = mapper.getNext();
        while (convert != null && convert.getOpcode() < 0) convert = convert.getNext();
        if (!(convert instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !call.owner.equals("java/util/Optional") || !call.name.equals("map")
                || !call.desc.equals("(Ljava/util/function/Function;)Ljava/util/Optional;")) {
            throw new IllegalStateException("Unexpected SecureJar 3.x module URI conversion");
        }
        open.instructions.insertBefore(loadName, new FieldInsnNode(
                Opcodes.GETFIELD, "cpw/mods/jarhandling/impl/Jar", "contents",
                "Lcpw/mods/jarhandling/impl/JarContentsImpl;"));
        open.instructions.insertBefore(findFile, new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "cpw/mods/jarhandling/impl/JarContentsImpl", "lightspeed$findPath",
                "(Ljava/lang/String;)Ljava/util/Optional;", false));
        open.instructions.remove(findFile);
        open.instructions.remove(mapper);
        open.instructions.remove(convert);
        return AsmPatchSupport.write(node);
    }
}

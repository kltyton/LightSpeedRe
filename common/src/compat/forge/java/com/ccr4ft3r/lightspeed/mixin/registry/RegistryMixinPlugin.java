package com.ccr4ft3r.lightspeed.mixin.registry;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Selects the holder routing hooks for Forge's legacy or native per-registry dispatch. */
public final class RegistryMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> LEGACY_HOLDER_MIXINS = Set.of(
            "com.ccr4ft3r.lightspeed.mixin.registry.RegistryObjectAccessor",
            "com.ccr4ft3r.lightspeed.mixin.registry.RegistryObjectHolderMixin",
            "com.ccr4ft3r.lightspeed.mixin.registry.ObjectHolderRegistryMixin");
    private static final String FORGE_HOOKS_MODIFY_BAKING_MIXIN =
            "com.ccr4ft3r.lightspeed.mixin.client.ForgeHooksClientModifyBakingMixin";
    private Boolean legacyHolderDispatch;
    private String emfModelSelector;
    private Boolean modernFixModelBakePresent;

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FORGE_HOOKS_MODIFY_BAKING_MIXIN.equals(mixinClassName)) {
            return hasModernFixModelBakeHelper();
        }
        if (mixinClassName.startsWith("com.ccr4ft3r.lightspeed.mixin.compat.emf.")) {
            if (emfModelSelector == null) {
                try {
                    ClassNode manager = MixinService.getService().getBytecodeProvider()
                            .getClassNode(targetClassName);
                    emfModelSelector = manager.fields.stream()
                            .filter(field -> field.name.equals("currentSpecifiedModelLoading"))
                            .map(field -> field.desc)
                            .findFirst().orElseThrow(() -> new IllegalStateException("EMF model selector is missing"));
                } catch (ClassNotFoundException exception) {
                    return false;
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot read EMF's model selector ABI", exception);
                }
            }
            return switch (emfModelSelector) {
                case "Ljava/lang/String;" -> mixinClassName.endsWith(".EmfModelLoadingContextMixin");
                case "Ljava/lang/ThreadLocal;" -> mixinClassName.endsWith(".EmfThreadLocalModelLoadingContextMixin");
                default -> throw new IllegalStateException("Unsupported EMF model selector " + emfModelSelector);
            };
        }
        if (!LEGACY_HOLDER_MIXINS.contains(mixinClassName)) {
            return true;
        }
        if (legacyHolderDispatch == null) {
            try {
                var bytecode = MixinService.getService().getBytecodeProvider();
                ClassNode registryObject = bytecode.getClassNode("net.minecraftforge.registries.RegistryObject");
                legacyHolderDispatch = registryObject.fields.stream()
                        .anyMatch(field -> field.name.equals("optionalRegistry") && field.desc.equals("Z"));
                if (!legacyHolderDispatch) {
                    ClassNode holders = bytecode.getClassNode("net.minecraftforge.registries.ObjectHolderRegistry");
                    if (holders.methods.stream().noneMatch(method -> method.name.equals("addHandler")
                            && method.desc.equals("(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Runnable;)V"))) {
                        throw new IllegalStateException("Forge provides neither legacy nor native per-registry holder dispatch");
                    }
                }
            } catch (ClassNotFoundException | IOException exception) {
                throw new IllegalStateException("Cannot read Forge's registry holder dispatch ABI", exception);
            }
        }
        return legacyHolderDispatch;
    }

    @Override
    public void onLoad(String mixinPackage) { }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }

    private static final String EXPECTED_MODIFY_BAKING_INDY_DESC =
            "(Lorg/embeddedt/modernfix/forge/dynresources/ModelBakeEventHelper;"
                    + "Lnet/minecraftforge/client/event/ModelEvent$ModifyBakingResult;"
                    + "Ljava/util/Map;Ljava/lang/reflect/Method;)Ljava/util/function/BiConsumer;";

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!FORGE_HOOKS_MODIFY_BAKING_MIXIN.equals(mixinClassName)) {
            return;
        }
        for (MethodNode method : targetClass.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy
                        && "accept".equals(indy.name)
                        && indy.desc.endsWith(")Ljava/util/function/BiConsumer;")
                        && indy.desc.contains("Lorg/embeddedt/modernfix/forge/dynresources/ModelBakeEventHelper;")) {
                    AbstractInsnNode next = indy.getNext();
                    while (next != null && (next.getType() == AbstractInsnNode.LABEL
                            || next.getType() == AbstractInsnNode.LINE
                            || next.getType() == AbstractInsnNode.FRAME)) {
                        next = next.getNext();
                    }
                    if (EXPECTED_MODIFY_BAKING_INDY_DESC.equals(indy.desc)
                            && next instanceof MethodInsnNode forEachCall
                            && forEachCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "net/minecraftforge/fml/ModList".equals(forEachCall.owner)
                            && "forEachModContainer".equals(forEachCall.name)
                            && "(Ljava/util/function/BiConsumer;)V".equals(forEachCall.desc)) {
                        // The original consumer owns ModernFix's registry wrapper and event callbacks.
                        // Its captured timing map is mutable and is shared between the parallel calls.
                        method.instructions.insertBefore(indy, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                        method.instructions.insertBefore(indy, new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "java/util/Collections",
                                "synchronizedMap",
                                "(Ljava/util/Map;)Ljava/util/Map;",
                                false
                        ));
                        method.instructions.insertBefore(indy, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                        method.instructions.set(forEachCall, new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "com/ccr4ft3r/lightspeed/startup/client/ModelBakeHelperSupport",
                                "dispatchModifyBakingResult",
                                "(Lnet/minecraftforge/fml/ModList;Ljava/util/function/BiConsumer;)V",
                                false
                        ));
                        return;
                    }
                    // Unknown captured shape: leave ModernFix's original consumer intact.
                    return;
                }
            }
        }
    }

    private boolean hasModernFixModelBakeHelper() {
        if (modernFixModelBakePresent == null) {
            try {
                MixinService.getService().getBytecodeProvider()
                        .getClassNode("org.embeddedt.modernfix.forge.dynresources.ModelBakeEventHelper");
                modernFixModelBakePresent = true;
            } catch (ClassNotFoundException | IOException ignored) {
                modernFixModelBakePresent = false;
            }
        }
        return modernFixModelBakePresent;
    }
}

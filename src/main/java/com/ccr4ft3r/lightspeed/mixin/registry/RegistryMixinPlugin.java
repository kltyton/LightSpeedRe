package com.ccr4ft3r.lightspeed.mixin.registry;

import org.objectweb.asm.tree.ClassNode;
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
    private Boolean legacyHolderDispatch;

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}

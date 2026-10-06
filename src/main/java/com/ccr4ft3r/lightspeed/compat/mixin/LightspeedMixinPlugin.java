package com.ccr4ft3r.lightspeed.compat.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Installs known companion callbacks before the pending Mixin configurations are applied. */
public final class LightspeedMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public List<String> getMixins() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        if (otherTargets.contains("com.brandon3055.draconicevolution.blocks.tileentity.StabilizedSpawnerLogic")) {
            JdteSpawnerAccessorCallbacks.install();
        }
    }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
}

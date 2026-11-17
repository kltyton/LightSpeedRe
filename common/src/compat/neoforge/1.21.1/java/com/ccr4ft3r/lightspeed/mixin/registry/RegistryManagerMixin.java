package com.ccr4ft3r.lightspeed.mixin.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Parallelizes read-only frozen registry snapshot creation across registries during mod loading. */
@Mixin(value = RegistryManager.class, remap = false)
public abstract class RegistryManagerMixin {
    @Inject(method = "takeSnapshot(Lnet/neoforged/neoforge/registries/RegistryManager$SnapshotType;)Ljava/util/Map;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void lightspeed$parallelTakeSnapshot(RegistryManager.SnapshotType type,
            CallbackInfoReturnable<Map<ResourceLocation, RegistrySnapshot>> cir) {
        if (type == RegistryManager.SnapshotType.FULL) {
            return;
        }
        Map<ResourceLocation, RegistrySnapshot> result = BuiltInRegistries.REGISTRY.stream()
                .filter(registry -> type != RegistryManager.SnapshotType.SYNC_TO_CLIENT || registry.doesSync())
                .parallel()
                .collect(Collectors.toConcurrentMap(
                        registry -> registry.key().location(),
                        registry -> new RegistrySnapshot(registry, false)));
        cir.setReturnValue(new HashMap<>(result));
    }
}

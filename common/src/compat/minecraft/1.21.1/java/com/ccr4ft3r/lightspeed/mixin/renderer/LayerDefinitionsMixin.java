package com.ccr4ft3r.lightspeed.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps model layers aligned with the live boat enum after initialization-time extensions. */
@Mixin(LayerDefinitions.class)
public abstract class LayerDefinitionsMixin {
    @WrapOperation(method = "createRoots", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/Boat$Type;values()[Lnet/minecraft/world/entity/vehicle/Boat$Type;"))
    private static Boat.Type[] lightspeed$liveBoatTypes(Operation<Boat.Type[]> original) {
        // Returning the original array keeps Jasione from substituting a stale Class.getEnumConstants snapshot.
        return original.call();
    }
}

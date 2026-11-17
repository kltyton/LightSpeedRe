package com.ccr4ft3r.lightspeed.mixin.client;

import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;

/**
 * High-priority marker mixin so {@link com.ccr4ft3r.lightspeed.mixin.registry.RegistryMixinPlugin#postApply}
 * runs after ModernFix merges its {@code ModifyBakingResult} dispatcher into {@link ForgeHooksClient}.
 */
@Mixin(value = ForgeHooksClient.class, priority = 2000, remap = false)
public abstract class ForgeHooksClientModifyBakingMixin {
}

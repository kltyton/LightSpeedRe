package com.ccr4ft3r.lightspeed.mixin.client;

import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;

/**
 * High-priority marker mixin so {@link com.ccr4ft3r.lightspeed.compat.mixin.LightspeedMixinPlugin#postApply}
 * runs after ModernFix merges its {@code ModifyBakingResult} dispatcher into {@link ClientHooks}.
 */
@Mixin(value = ClientHooks.class, priority = 2000, remap = false)
public abstract class ClientHooksModifyBakingMixin {
}

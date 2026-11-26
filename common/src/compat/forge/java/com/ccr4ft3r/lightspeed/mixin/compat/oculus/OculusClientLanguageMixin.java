package com.ccr4ft3r.lightspeed.mixin.compat.oculus;

import com.google.common.collect.Lists;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Keeps Oculus's shader language preferences with the dictionary that was loaded. */
@Mixin(ClientLanguage.class)
public abstract class OculusClientLanguageMixin {
    @Unique
    private List<String> lightspeed$shaderLanguageOrder;

    @Inject(method = "loadFrom", at = @At("RETURN"), require = 1)
    private static void lightspeed$bindShaderLanguageOrder(ResourceManager resources, List<String> definitions,
            boolean bidirectional, CallbackInfoReturnable<ClientLanguage> result) {
        OculusClientLanguageMixin dictionary = (OculusClientLanguageMixin) (Object) result.getReturnValue();
        dictionary.lightspeed$shaderLanguageOrder = List.copyOf(Lists.reverse(definitions));
    }

    @Unique
    private List<String> lightspeed$shaderLanguageOrder(List<String> legacyOrder) {
        if (this.lightspeed$shaderLanguageOrder != null) {
            return this.lightspeed$shaderLanguageOrder;
        }
        // Preserve Oculus's behavior for dictionaries constructed outside the factory.
        synchronized (ClientLanguage.class) {
            return List.copyOf(legacyOrder);
        }
    }
}

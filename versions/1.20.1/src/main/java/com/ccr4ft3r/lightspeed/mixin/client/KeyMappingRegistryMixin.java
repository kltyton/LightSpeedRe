package com.ccr4ft3r.lightspeed.mixin.client;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(KeyMapping.class)
public abstract class KeyMappingRegistryMixin {
    @Shadow @Final @Mutable private static Map<String, KeyMapping> ALL;
    @Shadow @Final @Mutable private static Set<String> CATEGORIES;
    @Shadow @Final @Mutable private static Map<String, Integer> CATEGORY_SORT_ORDER;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void lightspeed$allowConcurrentRegistration(CallbackInfo callback) {
        ALL = new ConcurrentHashMap<>(ALL);
        CATEGORY_SORT_ORDER = new ConcurrentHashMap<>(CATEGORY_SORT_ORDER);
        Set<String> categories = ConcurrentHashMap.newKeySet();
        categories.addAll(CATEGORIES);
        CATEGORIES = categories;
    }
}

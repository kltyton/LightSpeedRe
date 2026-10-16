package com.ccr4ft3r.lightspeed.mixin.compat;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.reflect.Field;

/** Keeps LightLoad's reflection bridge from replacing Lightspeed's configured optimization flags. */
@Pseudo
@Mixin(targets = "com.lightload.compat.LightspeedInterop", remap = false, priority = 2000)
public abstract class LightLoadCacheFlagsMixin {
    @WrapOperation(method = "setBoolean", at = @At(value = "INVOKE",
            target = "Ljava/lang/reflect/Field;setBoolean(Ljava/lang/Object;Z)V"), remap = false)
    private static void lightspeed$preserveCacheFlags(Field field, Object owner, boolean value,
            Operation<Void> original) {
        if (field.getDeclaringClass() != GlobalCache.class) original.call(field, owner, value);
    }

    @WrapOperation(method = "applyModernFixMode", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V"), remap = false)
    private static void lightspeed$reportPreservedFlags(Logger logger, String message, Operation<Void> original) {
        original.call(logger, "[Lightload] LightspeedRe retains its configured resource and model optimizations");
    }
}

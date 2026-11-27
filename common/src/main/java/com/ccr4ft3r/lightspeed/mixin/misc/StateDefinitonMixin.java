package com.ccr4ft3r.lightspeed.mixin.misc;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import com.google.common.collect.ImmutableSortedMap;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(StateDefinition.class)
public class StateDefinitonMixin implements ICache {
    @Unique
    private static final int LIGHTSPEED$MIN_HASH_PROPERTIES = 5;

    @Shadow @Final private ImmutableSortedMap<String, Property<?>> propertiesByName;

    @Unique
    private volatile Map<String, Property<?>> lightspeed$propsByName;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void initReturnInjected(Function p_61052_, Object p_61053_, StateDefinition.Factory p_61054_, Map p_61055_, CallbackInfo ci) {
        if (GlobalCache.isEnabled && this.propertiesByName.size() >= LIGHTSPEED$MIN_HASH_PROPERTIES) {
            lightspeed$propsByName = new HashMap<>(this.propertiesByName);
            GlobalCache.add(this);
        }
    }

    @Inject(method = "getProperty", at = @At("HEAD"), cancellable = true)
    public void getPropertyHeadInjected(String p_61082_, CallbackInfoReturnable<Property<?>> cir) {
        Map<String, Property<?>> properties = lightspeed$propsByName;
        if (properties != null) {
            cir.setReturnValue(properties.get(p_61082_));
        }
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        lightspeed$propsByName = null;
    }
}

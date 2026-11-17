package com.ccr4ft3r.lightspeed.mixin.misc;

import com.mojang.serialization.codecs.BaseMapCodec;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Anchor mixin for replacing the O(N^2) {@code Object2ObjectArrayMap} accumulator in DFU 8
 * {@link BaseMapCodec#decode} via {@code LightspeedMixinPlugin.postApply}.
 */
@Mixin(value = BaseMapCodec.class, remap = false)
public interface BaseMapCodecMixin {
}

package com.ccr4ft3r.lightspeed.mixin.registry;

import com.ccr4ft3r.lightspeed.startup.compat.IdMapperCapacity;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/** Pre-sizes IdMapper backing structures before bulk registration avoids repeated rehashes. */
@Mixin(IdMapper.class)
public abstract class IdMapperCapacityMixin<T> implements IdMapperCapacity {
    @Shadow @Final private Reference2IntMap<T> tToId;
    @Shadow @Final private List<T> idToT;

    @Override
    public void lightspeed$ensureCapacity(int expectedSize) {
        if (expectedSize <= 0) {
            return;
        }
        if (this.tToId instanceof Reference2IntOpenHashMap<T> map) {
            map.ensureCapacity(expectedSize);
        }
        if (this.idToT instanceof ArrayList<T> list) {
            list.ensureCapacity(expectedSize);
        }
    }
}

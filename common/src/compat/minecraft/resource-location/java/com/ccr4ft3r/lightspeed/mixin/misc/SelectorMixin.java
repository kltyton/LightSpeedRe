package com.ccr4ft3r.lightspeed.mixin.misc;

import com.ccr4ft3r.lightspeed.cache.GlobalCache;
import com.ccr4ft3r.lightspeed.interfaces.ICache;
import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Mixin(Selector.class)
public class SelectorMixin implements ICache {

    /** Inline single-definition cache slot storing {StateDefinition, Predicate} to avoid Map allocation. */
    @Unique
    private volatile Object[] lightspeed$single;

    @Unique
    private volatile Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> lightspeed$overflow;

    @Unique
    private boolean lightspeed$registered;

    @SuppressWarnings("unchecked")
    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true)
    public void getPredicateHeadInjected(StateDefinition<Block, BlockState> definition,
                                         CallbackInfoReturnable<Predicate<BlockState>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Object[] single = lightspeed$single;
        if (single != null && single[0] == definition) {
            cir.setReturnValue((Predicate<BlockState>) single[1]);
            return;
        }
        Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> overflow = lightspeed$overflow;
        if (overflow != null) {
            Predicate<BlockState> predicate = overflow.get(definition);
            if (predicate != null) {
                cir.setReturnValue(predicate);
            }
        }
    }

    @Inject(method = "getPredicate", at = @At("RETURN"))
    public void getPredicateReturnInjected(StateDefinition<Block, BlockState> definition,
                                           CallbackInfoReturnable<Predicate<BlockState>> cir) {
        if (!GlobalCache.isEnabled) {
            return;
        }
        Predicate<BlockState> predicate = cir.getReturnValue();
        if (predicate == null) {
            return;
        }
        Object[] single = lightspeed$single;
        if (single != null && single[0] == definition) {
            return;
        }
        synchronized (this) {
            if (!GlobalCache.isEnabled) {
                return;
            }
            if (!lightspeed$registered) {
                lightspeed$registered = true;
                GlobalCache.add(this);
            }
            Object[] current = lightspeed$single;
            if (current == null) {
                lightspeed$single = new Object[]{definition, predicate};
                return;
            }
            if (current[0] == definition) {
                return;
            }
            Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> overflow = lightspeed$overflow;
            if (overflow == null) {
                overflow = new ConcurrentHashMap<>(4);
                lightspeed$overflow = overflow;
            }
            overflow.put(definition, predicate);
        }
    }

    @Override
    public void lightspeed$persistAndClearCache() {
        synchronized (this) {
            lightspeed$single = null;
            Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> overflow = lightspeed$overflow;
            if (overflow != null) {
                overflow.clear();
                lightspeed$overflow = null;
            }
            lightspeed$registered = false;
        }
    }
}

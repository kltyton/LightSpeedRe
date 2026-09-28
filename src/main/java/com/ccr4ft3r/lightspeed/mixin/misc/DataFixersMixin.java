package com.ccr4ft3r.lightspeed.mixin.misc;

import com.ccr4ft3r.lightspeed.cache.dfu.LazyDataFixer;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

@Mixin(value = DataFixers.class, priority = 900)
public abstract class DataFixersMixin {
    @Redirect(
            method = "<clinit>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/datafix/DataFixers;createFixerUpper(Ljava/util/Set;)Lcom/mojang/datafixers/DataFixer;"),
            require = 0
    )
    private static DataFixer lightspeed$lazyDataFixer(Set<DSL.TypeReference> references) {
        Set<DSL.TypeReference> copy = Set.copyOf(references);
        return new LazyDataFixer(() -> DataFixersInvoker.lightspeed$createFixerUpper(copy));
    }
}

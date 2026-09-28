package com.ccr4ft3r.lightspeed.mixin.misc;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(DataFixers.class)
public interface DataFixersInvoker {
    @Invoker("createFixerUpper")
    static DataFixer lightspeed$createFixerUpper(Set<DSL.TypeReference> references) {
        throw new AssertionError();
    }
}

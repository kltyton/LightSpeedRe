package com.ccr4ft3r.lightspeed.mixin.model;

import com.ccr4ft3r.lightspeed.client.model.ModelVariantTable;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.forge.dynresources.ModelLocationBuilder", remap = false)
public abstract class ModernFixModelLocationBuilderMixin {
    @Unique
    private final ModelVariantTable lightspeed$variantTable = new ModelVariantTable();

    @Inject(method = "generateForBlock", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/Lists;cartesianProduct(Ljava/util/List;)Ljava/util/List;"),
            cancellable = true, require = 0, remap = false)
    private void lightspeed$reuseVariants(Set<ResourceLocation> locations, Block block,
            ResourceLocation blockId, CallbackInfo ci, @Local List<List<String>> options) {
        List<String> variants = lightspeed$variantTable.variants(options);
        if (variants == null) {
            return;
        }
        for (String variant : variants) {
            locations.add(new ModelResourceLocation(blockId, variant));
        }
        ci.cancel();
    }
}

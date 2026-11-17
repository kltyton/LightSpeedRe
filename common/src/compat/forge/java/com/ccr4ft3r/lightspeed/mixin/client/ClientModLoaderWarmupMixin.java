package com.ccr4ft3r.lightspeed.mixin.client;

import com.ccr4ft3r.lightspeed.startup.compat.ModPackPrewarmSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.client.loading.ClientModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientModLoader.class, remap = false)
public abstract class ClientModLoaderWarmupMixin {
    @Inject(method = "begin", at = @At("HEAD"))
    private static void lightspeed$startModPackPrewarm(
            Minecraft minecraft,
            PackRepository packRepository,
            ReloadableResourceManager resourceManager,
            CallbackInfo ci
    ) {
        ModPackPrewarmSupport.startAsyncPrewarm();
    }

    @Inject(
            method = "begin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/resource/ResourcePackLoader;loadResourcePacks(Lnet/minecraft/server/packs/repository/PackRepository;Ljava/util/function/Function;)V"
            )
    )
    private static void lightspeed$awaitModPackPrewarm(
            Minecraft minecraft,
            PackRepository packRepository,
            ReloadableResourceManager resourceManager,
            CallbackInfo ci
    ) {
        ModPackPrewarmSupport.awaitPrewarm();
    }
}

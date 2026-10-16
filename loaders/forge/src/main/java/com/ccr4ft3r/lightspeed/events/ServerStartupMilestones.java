package com.ccr4ft3r.lightspeed.events;

import com.ccr4ft3r.lightspeed.ModConstants;
import com.ccr4ft3r.lightspeed.startup.metrics.StartupMetrics;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ModConstants.MOD_ID)
public final class ServerStartupMilestones {
    private ServerStartupMilestones() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        StartupMetrics.mark("server-started");
    }
}

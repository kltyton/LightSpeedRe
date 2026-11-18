package com.ccr4ft3r.lightspeed.client.resources;

import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Map;

/** Coordinates background preparation of language metadata and translations ahead of synchronous apply. */
public final class AsyncLanguageReload {
    private AsyncLanguageReload() {
    }

    public interface PreparedHost {
        PreparedLanguage lightspeed$prepareLanguage(ResourceManager resourceManager, String selectedCode);

        void lightspeed$runWithPreparedLanguage(PreparedLanguage prepared, Runnable apply);
    }

    public record PreparedLanguage(
            ResourceManager resourceManager,
            String expectedCode,
            Map<String, LanguageInfo> languages,
            List<String> loadOrder,
            boolean bidirectional,
            ClientLanguage clientLanguage) {
        public boolean matchesOrder(List<String> actualOrder, boolean actualBidirectional) {
            return bidirectional == actualBidirectional && loadOrder.equals(actualOrder);
        }
    }
}

package com.ccr4ft3r.lightspeed.client.resources;

import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Coordinates background preparation of language metadata and translations ahead of synchronous apply. */
public final class AsyncLanguageReload {
    private AsyncLanguageReload() {
    }

    public interface PreparedHost {
        void lightspeed$beginAsyncPrepare(ResourceManager resourceManager,
                                          CompletableFuture<PreparedLanguage> preparedFuture);

        CompletableFuture<PreparedLanguage> lightspeed$consumePreparedFuture();

        PreparedLanguage lightspeed$prepareLanguage(ResourceManager resourceManager);

        void lightspeed$setActivePrepared(PreparedLanguage prepared);

        PreparedLanguage lightspeed$activePrepared();
    }

    public record PreparedLanguage(
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

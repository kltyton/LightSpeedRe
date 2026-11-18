package com.ccr4ft3r.lightspeed.client.resources;

import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Map;

public record PreparedLanguageReload(
        ResourceManager resourceManager,
        String selectedCode,
        Map<String, LanguageInfo> languages,
        List<String> loadOrder,
        boolean bidirectional,
        ClientLanguage clientLanguage
) {
}

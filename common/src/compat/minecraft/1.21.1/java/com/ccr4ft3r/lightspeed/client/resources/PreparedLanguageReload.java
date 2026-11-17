package com.ccr4ft3r.lightspeed.client.resources;

import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;

import java.util.List;
import java.util.Map;

public record PreparedLanguageReload(
        String selectedCode,
        Map<String, LanguageInfo> languages,
        List<String> loadOrder,
        boolean bidirectional,
        ClientLanguage clientLanguage
) {
}

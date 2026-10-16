package io.github.kltyton.temple.ui;

import java.util.Locale;
import java.util.Map;

public final class UiText {
    private static final Map<String, String[]> WORDS = Map.ofEntries(
            Map.entry("language", new String[]{"界面语言", "Language"}),
            Map.entry("buildDsl", new String[]{"Gradle 脚本语言", "Gradle script language"}),
            Map.entry("minecraft", new String[]{"Minecraft 版本", "Minecraft version"}),
            Map.entry("loader", new String[]{"加载器", "Loader"}),
            Map.entry("loaderVersion", new String[]{"加载器版本", "Loader version"}),
            Map.entry("fabricApi", new String[]{"Fabric API 版本", "Fabric API version"}),
            Map.entry("toolchain", new String[]{"工具链", "Toolchain"}),
            Map.entry("preview", new String[]{"显示预发布版本", "Include preview versions"}),
            Map.entry("add", new String[]{"添加目标", "Add target"}),
            Map.entry("refresh", new String[]{"刷新版本目录", "Refresh versions"}),
            Map.entry("remove", new String[]{"移除所选目标", "Remove selected"}),
            Map.entry("loading", new String[]{"正在读取官方版本目录…", "Loading official version catalogs…"}),
            Map.entry("loadingJava", new String[]{"正在读取 Minecraft 的 Java 要求…", "Loading Minecraft Java requirement…"}),
            Map.entry("loadingVersions", new String[]{"正在读取兼容版本…", "Loading compatible versions…"}),
            Map.entry("downloadFailed", new String[]{"版本目录读取失败，请刷新重试。", "Version download failed. Refresh to retry."}),
            Map.entry("javaFailed", new String[]{"无法读取 Minecraft 的 Java 要求", "Could not read Minecraft Java requirement"}),
            Map.entry("duplicate", new String[]{"列表中已经有这个目标。", "This target is already in the list."}),
            Map.entry("selectTarget", new String[]{"至少添加一个目标", "Select at least one target"}),
            Map.entry("notReady", new String[]{"请等待兼容版本和工具链读取完成。", "Wait for compatible versions and toolchain metadata."}),
            Map.entry("recommended", new String[]{"推荐", "Recommended"}),
            Map.entry("releases", new String[]{"个 Minecraft 正式版本", "Minecraft releases"}),
            Map.entry("selected", new String[]{"个目标已添加", "targets selected"}),
            Map.entry("createTitle", new String[]{"KltytonTemple · 创建工程", "KltytonTemple · Create project"}),
            Map.entry("addTitle", new String[]{"KltytonTemple · 新增目标", "KltytonTemple · Add targets"}),
            Map.entry("confirm", new String[]{"确定", "Confirm"}),
            Map.entry("cancel", new String[]{"取消", "Cancel"}),
            Map.entry("name", new String[]{"Mod 名称", "Mod name"}),
            Map.entry("package", new String[]{"Java 包名", "Java package"}),
            Map.entry("authors", new String[]{"作者", "Authors"}),
            Map.entry("version", new String[]{"项目版本", "Project version"}),
            Map.entry("license", new String[]{"许可证", "License"}),
            Map.entry("description", new String[]{"描述", "Description"}),
            Map.entry("singleLineDescription", new String[]{"描述不能换行。", "Description must be a single line."}),
            Map.entry("directory", new String[]{"新工程目录", "New project directory"}),
            Map.entry("browse", new String[]{"浏览…", "Browse…"}),
            Map.entry("added", new String[]{"目标已添加。新版本的游戏代码仍需按对应 API 适配。", "Targets added. Adapt game code to the selected version APIs."}),
            Map.entry("choices", new String[]{"可选版本", "available versions"}),
            Map.entry("errorDetail", new String[]{"查看错误详情", "Show error details"}),
            Map.entry("chooseDirectory", new String[]{"请选择新工程目录", "Choose a new project directory"}),
            Map.entry("createFailed", new String[]{"无法创建工程", "Could not create project"}),
            Map.entry("addFailed", new String[]{"无法添加目标", "Could not add targets"}),
            Map.entry("created", new String[]{"工程已创建，可在 IDEA 中打开：", "Project created. Open it in IDEA:"})
    );
    private final boolean chinese;

    public UiText(Locale locale) {
        chinese = "zh".equals(locale.getLanguage());
    }

    public static UiText system() {
        return new UiText(Locale.getDefault());
    }

    public String get(String key) {
        String[] values = WORDS.get(key);
        if (values == null) throw new IllegalArgumentException("Unknown UI text: " + key);
        return values[chinese ? 0 : 1];
    }
}

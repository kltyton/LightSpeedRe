package io.github.kltyton.temple.catalog;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;

public final class VersionCatalog {
    private final Function<String, Object> json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final Map<String, String> gameDocuments = new HashMap<>();
    private final Map<String, List<String>> forge = new HashMap<>(), neo = new HashMap<>();
    private final Map<String, List<String>> api = new ConcurrentHashMap<>();
    private final Map<String, Integer> javaVersions = new ConcurrentHashMap<>();
    private final Set<String> fabricGames = new HashSet<>();
    private final Map<String, List<String>> fabricByGame = new ConcurrentHashMap<>();
    private final Set<String> previewVersions = ConcurrentHashMap.newKeySet();
    private String recommendedFabricLoader;

    public VersionCatalog(Function<String, Object> json) {
        this.json = json;
    }

    public void load() throws Exception {
        Map<String, String> sources = Map.of(
                "game", "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "loader", "https://meta.fabricmc.net/v2/versions/loader",
                "fabricGame", "https://meta.fabricmc.net/v2/versions/game",
                "forge", "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
                "neo", "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        Map<String, CompletableFuture<String>> downloads = new HashMap<>();
        sources.forEach((name, url) -> downloads.put(name, download(url)));
        CompletableFuture.allOf(downloads.values().toArray(CompletableFuture[]::new)).join();
        Map<?, ?> manifest = (Map<?, ?>) json.apply(downloads.get("game").join());
        for (Object entry : (List<?>) manifest.get("versions")) {
            Map<?, ?> version = (Map<?, ?>) entry;
            String id = version.get("id").toString();
            if ("release".equals(version.get("type")) && id.matches("[0-9]+(\\.[0-9]+)*") && compare(id, "1.17") >= 0)
                gameDocuments.put(id, version.get("url").toString());
        }
        for (Object entry : (List<?>) json.apply(downloads.get("fabricGame").join()))
            fabricGames.add(((Map<?, ?>) entry).get("version").toString());
        List<?> loaders = (List<?>) json.apply(downloads.get("loader").join());
        recommendedFabricLoader = loaders.stream().map(value -> (Map<?, ?>) value)
                .filter(value -> Boolean.TRUE.equals(value.get("stable")))
                .map(value -> value.get("version").toString()).findFirst().orElse(null);
        for (String version : xmlVersions(downloads.get("forge").join())) {
            int split = version.indexOf('-');
            if (split > 0)
                forge.computeIfAbsent(version.substring(0, split), key -> new ArrayList<>()).add(version.substring(split + 1));
        }
        for (String version : xmlVersions(downloads.get("neo").join())) {
            String numeric = version.replaceFirst("-.*$", "");
            String prefix = numeric.substring(0, numeric.lastIndexOf('.'));
            String minecraft = prefix.startsWith("26.") ? prefix : "1." + prefix;
            if (minecraft.endsWith(".0")) minecraft = minecraft.substring(0, minecraft.length() - 2);
            if (compare(minecraft, "1.20.6") >= 0)
                neo.computeIfAbsent(minecraft, key -> new ArrayList<>()).add(version);
        }
        for (Map<String, List<String>> map : List.of(forge, neo))
            map.values().forEach(values -> values.sort((a, b) -> compare(b, a)));
    }

    public List<String> minecraftVersions() {
        return gameDocuments.keySet().stream().filter(mc -> !loaders(mc).isEmpty()).sorted((a, b) -> compare(b, a)).toList();
    }

    public List<String> loaders(String minecraft) {
        List<String> result = new ArrayList<>();
        if (fabricGames.contains(minecraft)) result.add("fabric");
        if (forge.containsKey(minecraft) && compare(minecraft, "26") < 0) result.add("forge");
        if (neo.containsKey(minecraft)) result.add("neoforge");
        return result;
    }

    public List<String> loaderVersions(String loader, String minecraft) {
        return switch (loader) {
            case "fabric" -> fabricByGame.getOrDefault(minecraft, List.of());
            case "forge" -> forge.getOrDefault(minecraft, List.of());
            case "neoforge" -> neo.getOrDefault(minecraft, List.of());
            default -> throw new IllegalArgumentException("Unknown loader: " + loader);
        };
    }

    public List<String> apiVersions(String minecraft) {
        return api.getOrDefault(minecraft, List.of());
    }

    public void loadCompatibleVersions(String minecraft) {
        if (fabricByGame.containsKey(minecraft)) return;
        String game = URLEncoder.encode(minecraft, StandardCharsets.UTF_8);
        String query = URLEncoder.encode("[\"" + minecraft + "\"]", StandardCharsets.UTF_8);
        CompletableFuture<String> loader = download("https://meta.fabricmc.net/v2/versions/loader/" + game);
        CompletableFuture<String> apiVersions = download("https://api.modrinth.com/v2/project/fabric-api/version?include_changelog=false&game_versions=" + query);
        CompletableFuture.allOf(loader, apiVersions).join();
        List<String> compatible = ((List<?>) json.apply(loader.join())).stream()
                .map(value -> (Map<?, ?>) ((Map<?, ?>) value).get("loader"))
                .map(value -> value.get("version").toString()).distinct().toList();
        List<String> apiChoices = new ArrayList<>();
        for (Object item : (List<?>) json.apply(apiVersions.join())) {
            Map<?, ?> release = (Map<?, ?>) item;
            String version = release.get("version_number").toString();
            apiChoices.add(version);
            if (!"release".equals(release.get("version_type"))) previewVersions.add(version);
        }
        fabricByGame.put(minecraft, compatible);
        api.put(minecraft, apiChoices.stream().distinct().toList());
    }

    public boolean preview(String version) {
        return previewVersions.contains(version) || version.matches("(?i).*(?:alpha|beta|snapshot|[.-]rc|[.-]pre).*");
    }

    public String recommendedFabricLoader() {
        return recommendedFabricLoader;
    }

    public int javaVersion(String minecraft) {
        return javaVersions.computeIfAbsent(minecraft, key -> {
            Map<?, ?> document = (Map<?, ?>) json.apply(download(gameDocuments.get(key)).join());
            Map<?, ?> java = (Map<?, ?>) document.get("javaVersion");
            if (java == null || !(java.get("majorVersion") instanceof Number major))
                throw new IllegalArgumentException("Minecraft metadata has no Java version: " + key);
            return major.intValue();
        });
    }

    private CompletableFuture<String> download(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "KltytonTemple/1.2.0 (github.com/kltyton/KltytonTemple)")
                .timeout(Duration.ofSeconds(30)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200)
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + url);
            return response.body();
        });
    }

    private static List<String> xmlVersions(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getElementsByTagName("version");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent().trim());
        return result;
    }

    public static int compare(String left, String right) {
        String[] a = left.split("[^0-9]+"), b = right.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return left.compareTo(right);
    }
}

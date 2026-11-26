package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.mojang.blaze3d.shaders.Program;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderSourceIdentity {
    private static final Pattern MOJ_IMPORT = Pattern.compile(
            "^[\\t ]*#moj_import[\\t ]+(?:<([^>\\r\\n]+)>|\"([^\"\\r\\n]+)\")");
    private static final Map<ResourceProvider, Map<String, String>> RAW_DIGEST_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ShaderSourceIdentity() {
    }

    public static String rawDigest(ResourceProvider provider, Program.Type type, String name) {
        if (!ShaderProgramSnapshot.enabled() || provider == null || type == null || name == null || name.isEmpty()) {
            return null;
        }
        String cacheKey = type.name() + '\0' + name;
        Map<String, String> providerCache = providerCache(provider);
        synchronized (providerCache) {
            String cached = providerCache.get(cacheKey);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
        }
        String computed = computeRawDigest(provider, type, name);
        synchronized (providerCache) {
            providerCache.put(cacheKey, computed == null ? "" : computed);
        }
        return computed;
    }

    private static String computeRawDigest(ResourceProvider provider, Program.Type type, String name) {
        ResourceLocation root = shaderLocation(type, name);
        if (root == null) {
            return null;
        }
        MessageDigest digest = sha256();
        update(digest, "lightspeed-raw-glsl-v1");
        update(digest, type.name());
        update(digest, name);
        Set<ResourceLocation> imported = new HashSet<>();
        if (!hashSourceTree(provider, root, digest, imported)) {
            return null;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean hashSourceTree(
            ResourceProvider provider,
            ResourceLocation location,
            MessageDigest digest,
            Set<ResourceLocation> imported) {
        Optional<Resource> resource = provider.getResource(location);
        if (resource.isEmpty()) {
            return false;
        }
        byte[] bytes;
        try (InputStream stream = resource.get().open()) {
            bytes = stream.readAllBytes();
        } catch (IOException exception) {
            return false;
        }
        update(digest, location.toString());
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);

        String source = new String(bytes, StandardCharsets.UTF_8);
        String currentDir = parentDirectory(location.getPath());
        boolean inBlockComment = false;
        int start = 0;
        int length = source.length();
        while (start < length) {
            int end = source.indexOf('\n', start);
            if (end < 0) {
                end = length;
            }
            String line = source.substring(start, end);
            start = end + 1;

            String uncommented = stripComments(line, inBlockComment);
            inBlockComment = endsInBlockComment(line, inBlockComment);
            if (uncommented.indexOf("#moj_import") < 0) {
                continue;
            }
            Matcher matcher = MOJ_IMPORT.matcher(uncommented);
            if (!matcher.find()) {
                continue;
            }
            String systemInclude = matcher.group(1);
            String localInclude = matcher.group(2);
            ResourceLocation target = systemInclude != null
                    ? resolveSystemInclude(systemInclude)
                    : resolveLocalInclude(location.getNamespace(), currentDir, localInclude);
            if (target == null) {
                return false;
            }
            update(digest, "import:" + target);
            if (imported.add(target) && !hashSourceTree(provider, target, digest, imported)) {
                return false;
            }
        }
        return true;
    }

    private static ResourceLocation shaderLocation(Program.Type type, String name) {
        ResourceLocation parsed = ResourceLocation.tryParse(name);
        if (parsed == null) {
            return null;
        }
        return ResourceLocation.tryBuild(parsed.getNamespace(),
                "shaders/core/" + parsed.getPath() + type.getExtension());
    }

    private static ResourceLocation resolveSystemInclude(String include) {
        ResourceLocation parsed = ResourceLocation.tryParse(include);
        if (parsed == null) {
            return null;
        }
        String normalized = normalizePath("shaders/include/" + parsed.getPath());
        return normalized == null ? null : ResourceLocation.tryBuild(parsed.getNamespace(), normalized);
    }

    private static ResourceLocation resolveLocalInclude(String namespace, String currentDir, String include) {
        String normalized = normalizePath(currentDir + include);
        return normalized == null ? null : ResourceLocation.tryBuild(namespace, normalized);
    }

    private static String parentDirectory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private static String normalizePath(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }

    private static String stripComments(String line, boolean inBlockComment) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        boolean block = inBlockComment;
        while (i < n) {
            char c = line.charAt(i);
            if (block) {
                if (c == '*' && i + 1 < n && line.charAt(i + 1) == '/') {
                    block = false;
                    i += 2;
                } else {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                break;
            } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                block = true;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean endsInBlockComment(String line, boolean inBlockComment) {
        int i = 0;
        int n = line.length();
        boolean block = inBlockComment;
        while (i < n) {
            char c = line.charAt(i);
            if (block) {
                if (c == '*' && i + 1 < n && line.charAt(i + 1) == '/') {
                    block = false;
                    i += 2;
                } else {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                break;
            } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                block = true;
                i += 2;
            } else {
                i++;
            }
        }
        return block;
    }

    private static Map<String, String> providerCache(ResourceProvider provider) {
        synchronized (RAW_DIGEST_CACHE) {
            return RAW_DIGEST_CACHE.computeIfAbsent(provider, ignored -> new HashMap<>());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

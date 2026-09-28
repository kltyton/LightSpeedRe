package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.mojang.blaze3d.shaders.Program;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class ShaderSourceIdentity {
    private static final ConcurrentHashMap<String, String> DIGESTS = new ConcurrentHashMap<>();

    private ShaderSourceIdentity() {
    }

    public static void record(Program.Type type, String name, List<String> processedSources) {
        if (!ShaderProgramSnapshot.enabled()) {
            return;
        }
        DIGESTS.put(key(type, name), processedDigest(type, name, processedSources));
    }

    static String processedDigest(Program.Type type, String name, List<String> processedSources) {
        MessageDigest digest = sha256();
        update(digest, type.name());
        update(digest, name);
        for (String source : processedSources) {
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String digest(Program.Type type, Program program) {
        return program == null ? null : DIGESTS.get(key(type, program.getName()));
    }

    static String digest(Program.Type type, String name) {
        return DIGESTS.get(key(type, name));
    }

    private static String key(Program.Type type, String name) {
        return type.name() + '\0' + name;
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

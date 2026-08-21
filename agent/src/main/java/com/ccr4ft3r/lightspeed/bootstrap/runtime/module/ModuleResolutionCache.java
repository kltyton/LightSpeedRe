package com.ccr4ft3r.lightspeed.bootstrap.runtime.module;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ModuleResolutionCache {
    private static final int MAGIC = 0x4c534d52;
    private static final int VERSION = 1;
    private static final int MAX_PLANS = 64;
    private static final int MAX_ROOTS = 20_000;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("lightspeed.moduleResolutionCache", "true"));
    private static final Path FILE = cacheDirectory().resolve("module-resolution-v1.bin");
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>(load());
    private static final Set<String> ACTIVE_KEYS = ConcurrentHashMap.newKeySet();

    private ModuleResolutionCache() {
    }

    public static Configuration resolveAndBind(ModuleFinder before, List<Configuration> parents,
                                               ModuleFinder after, Collection<String> roots) {
        if (!ENABLED) {
            return Configuration.resolveAndBind(before, parents, after, roots);
        }

        String key;
        try {
            key = inputFingerprint(before, parents, after, roots);
        } catch (RuntimeException exception) {
            FAILURES.increment();
            return Configuration.resolveAndBind(before, parents, after, roots);
        }
        ACTIVE_KEYS.add(key);

        Plan plan = PLANS.get(key);
        if (plan != null) {
            try {
                Configuration candidate = Configuration.resolve(before, parents, after, plan.roots());
                if (plan.graphDigest().equals(graphDigest(candidate))) {
                    HITS.increment();
                    return candidate;
                }
            } catch (RuntimeException exception) {
                FAILURES.increment();
            }
        }

        MISSES.increment();
        Configuration resolved = Configuration.resolveAndBind(before, parents, after, roots);
        List<String> resolvedRoots = resolved.modules().stream()
                .map(ResolvedModule::name)
                .sorted()
                .toList();
        PLANS.put(key, new Plan(resolvedRoots, graphDigest(resolved)));
        return resolved;
    }

    public static void persist() {
        if (!ENABLED || ACTIVE_KEYS.isEmpty()) {
            return;
        }
        List<Map.Entry<String, Plan>> active = ACTIVE_KEYS.stream()
                .map(key -> Map.entry(key, PLANS.get(key)))
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(FILE.getParent());
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(active.size());
                for (Map.Entry<String, Plan> entry : active) {
                    output.writeUTF(entry.getKey());
                    output.writeUTF(entry.getValue().graphDigest());
                    output.writeInt(entry.getValue().roots().size());
                    for (String root : entry.getValue().roots()) {
                        output.writeUTF(root);
                    }
                }
            }
            moveIntoPlace(temporary, FILE);
        } catch (IOException | RuntimeException exception) {
            FAILURES.increment();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                FAILURES.increment();
            }
        }
    }

    public static long hits() {
        return HITS.sum();
    }

    public static long misses() {
        return MISSES.sum();
    }

    public static long failures() {
        return FAILURES.sum();
    }

    private static Map<String, Plan> load() {
        if (!ENABLED || !Files.isRegularFile(FILE)) {
            return Map.of();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(FILE)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                return Map.of();
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_PLANS) {
                throw new IOException("invalid module plan count " + count);
            }
            Map<String, Plan> plans = new ConcurrentHashMap<>();
            for (int index = 0; index < count; index++) {
                String key = input.readUTF();
                String graph = input.readUTF();
                int roots = input.readInt();
                if (roots < 0 || roots > MAX_ROOTS) {
                    throw new IOException("invalid module root count " + roots);
                }
                List<String> names = new ArrayList<>(roots);
                for (int root = 0; root < roots; root++) {
                    names.add(input.readUTF());
                }
                plans.put(key, new Plan(List.copyOf(names), graph));
            }
            return plans;
        } catch (IOException | RuntimeException exception) {
            FAILURES.increment();
            return Map.of();
        }
    }

    private static String inputFingerprint(ModuleFinder before, List<Configuration> parents,
                                           ModuleFinder after, Collection<String> roots) {
        MessageDigest digest = sha256();
        append(digest, "lightspeed-module-resolution-v1");
        roots.stream().sorted().forEach(root -> append(digest, "root=" + root));
        appendFinder(digest, "before", before);
        appendFinder(digest, "after", after);
        for (int index = 0; index < parents.size(); index++) {
            appendConfigurationInput(digest, "parent." + index, parents.get(index),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void appendConfigurationInput(MessageDigest digest, String label, Configuration configuration,
                                                 Set<Configuration> visited) {
        if (!visited.add(configuration)) {
            return;
        }
        append(digest, label + ".graph=" + graphDigest(configuration));
        configuration.modules().stream().sorted(Comparator.comparing(ResolvedModule::name))
                .forEach(module -> appendReference(digest, label, module.reference()));
        for (int index = 0; index < configuration.parents().size(); index++) {
            appendConfigurationInput(digest, label + ".parent." + index, configuration.parents().get(index), visited);
        }
    }

    private static void appendFinder(MessageDigest digest, String label, ModuleFinder finder) {
        finder.findAll().stream()
                .sorted(Comparator.comparing(reference -> reference.descriptor().name()))
                .forEach(reference -> appendReference(digest, label, reference));
    }

    private static void appendReference(MessageDigest digest, String label, ModuleReference reference) {
        ModuleDescriptor descriptor = reference.descriptor();
        append(digest, label + ".name=" + descriptor.name());
        append(digest, label + ".version=" + descriptor.rawVersion().orElse(""));
        append(digest, label + ".location=" + reference.location().map(Object::toString).orElse(""));
        descriptor.modifiers().stream().map(Enum::name).sorted()
                .forEach(value -> append(digest, label + ".modifier=" + value));
        descriptor.packages().stream().sorted().forEach(value -> append(digest, label + ".package=" + value));
        descriptor.uses().stream().sorted().forEach(value -> append(digest, label + ".uses=" + value));
        descriptor.requires().stream().sorted(Comparator.comparing(ModuleDescriptor.Requires::name)).forEach(value -> {
            append(digest, label + ".requires=" + value.name());
            value.modifiers().stream().map(Enum::name).sorted()
                    .forEach(modifier -> append(digest, label + ".requires.modifier=" + modifier));
            append(digest, label + ".requires.version=" + value.rawCompiledVersion().orElse(""));
        });
        descriptor.exports().stream().sorted(Comparator.comparing(ModuleDescriptor.Exports::source)).forEach(value -> {
            append(digest, label + ".exports=" + value.source());
            value.targets().stream().sorted().forEach(target -> append(digest, label + ".exports.target=" + target));
        });
        descriptor.opens().stream().sorted(Comparator.comparing(ModuleDescriptor.Opens::source)).forEach(value -> {
            append(digest, label + ".opens=" + value.source());
            value.targets().stream().sorted().forEach(target -> append(digest, label + ".opens.target=" + target));
        });
        descriptor.provides().stream().sorted(Comparator.comparing(ModuleDescriptor.Provides::service)).forEach(value -> {
            append(digest, label + ".provides=" + value.service());
            value.providers().stream().sorted()
                    .forEach(provider -> append(digest, label + ".provider=" + provider));
        });
    }

    private static String graphDigest(Configuration configuration) {
        MessageDigest digest = sha256();
        configuration.modules().stream().sorted(Comparator.comparing(ResolvedModule::name)).forEach(module -> {
            append(digest, "module=" + module.name());
            module.reads().stream().map(ResolvedModule::name).sorted()
                    .forEach(read -> append(digest, "reads=" + read));
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void append(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path cacheDirectory() {
        String configured = System.getProperty("lightspeed.bootstrapCacheDir");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir", "."), "lightspeed-cache", "bootstrap")
                : Path.of(configured);
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Plan(List<String> roots, String graphDigest) {
    }
}

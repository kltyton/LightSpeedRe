package com.ccr4ft3r.lightspeed.bootstrap.runtime.transform;

import com.ccr4ft3r.lightspeed.bootstrap.runtime.RuntimeModuleAccess;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

public final class ClassPassThrough {
    private static final String MIXIN = "org.spongepowered.asm.launch.MixinLaunchPlugin";
    private static final String MIXIN_LEGACY = "org.spongepowered.asm.launch.MixinLaunchPluginLegacy";
    private static final String EVENT_BUS = "net.minecraftforge.eventbus.service.ModLauncherService";
    private static final String EVENT_ENGINE = "net.minecraftforge.eventbus.EventBusEngine";
    private static final String HANDLER = "org.spongepowered.asm.service.modlauncher.MixinTransformationHandler";
    private static final String TRACKER = "org.spongepowered.asm.service.modlauncher.ModLauncherClassTracker";
    private static final String ARGS_GENERATOR = "org.spongepowered.asm.mixin.injection.invoke.arg.ArgsClassGenerator";
    private static final String INNER_GENERATOR = "org.spongepowered.asm.mixin.transformer.InnerClassGenerator";
    private static final Map<String, String> SUPPORTED = Map.ofEntries(
            Map.entry(ARGS_GENERATOR, "bfbcdccd62b2e9b2c4fb7e9cd726fdf4a1926e0db6216a2e77f7d7389a4ed67b"),
            Map.entry(INNER_GENERATOR, "9778898fca03a83955a7ea069c9bc6f0c78052e78058f10fc02a2b344db45920"),
            Map.entry(HANDLER, "d7dfa5f9413ffc9e12927590248dff283988b4ad51383ae5bca27edee040c267"),
            Map.entry(TRACKER, "d51fb211571ccb83d4dda6cf173d789b58edd03cd0dd7afd407605b91b5340a7"),
            Map.entry(MIXIN, "b9ba4d07153113dfdd66736aa7553b1951b286db820fdec1a8349973c7e3c04e"),
            Map.entry(MIXIN_LEGACY, "eebe20b521483335da9fdce6b7a52a46581d6727faa207d5c3e41555badc992e"),
            Map.entry(EVENT_BUS, "ee55d63a1cceb5392bd9758cbc33fe121bc8e15aa553154987f50d54fa3a8c6a"),
            Map.entry(EVENT_ENGINE, "69bd2c020c8eec726d1c909e08aaf723b5cdad0280e4c43752835216f5fa6b41"),
            Map.entry("net.minecraftforge.fml.loading.RuntimeDistCleaner", "429042ff33a519b86ae500f1039b6bb4f8388676bf480b46f662b73874c9cc17"),
            Map.entry("net.minecraftforge.fml.common.asm.RuntimeEnumExtender", "7bff6553591fa3121bb831cc84cf9cbf67d2bf2e34b5b397888d811229d42c03"),
            Map.entry("net.minecraftforge.fml.common.asm.ObjectHolderDefinalize", "fb91279833973ea22f9a983347bf4004145374db96111f4f369ae454f17d3e8f"),
            Map.entry("net.minecraftforge.fml.common.asm.CapabilityTokenSubclass", "c69ac1dcd4dcea933a8eb4999e37837a5be611b093023f923b576b01abcc37a9"));
    private static final Map<String, String> SUPPORTED_CURRENT = Map.ofEntries(
            Map.entry(ARGS_GENERATOR, "c4afc3021acb9b8c34a92ff8d6a1ef0a7212210044400c845cd96db020e56073"),
            Map.entry(INNER_GENERATOR, "3bc1564942e50749f076d39ced485ae9a93785cd948f1fd657ee8cd84b1a23f2"),
            Map.entry(HANDLER, "50b45cbe77009ecf01008533e5cba432fa205ecb4484a7c0f4a9fac43c86ed5c"),
            Map.entry(TRACKER, "e1610cefc9058376f25143ca1be1ae8bd210a72e3f2528feb3e3ccedb67f41f3"),
            Map.entry(MIXIN, "f0cfbdd2d82d9bc729f5527a932222e6a6ef53280f5cd0e114a95ecf25b2d1df"),
            Map.entry(MIXIN_LEGACY, "bafd7a04c4b2466648f51702703e25b6a0819fb24c32f302ae36ea821887b7cf"),
            Map.entry(EVENT_BUS, "c6aa105da1bbb3b2b8dce1282528f1f3aed7746b7df0dd6fb96b89ca8270b18a"),
            Map.entry(EVENT_ENGINE, "679c4106e0e8d80d53f55a66521993008048a5d4ed207ab2b9cf68071df914bf"));
    private static final byte[][] ANNOTATIONS = {
            ascii("Lnet/minecraftforge/api/distmarker/OnlyIn;"),
            ascii("Lnet/minecraftforge/api/distmarker/OnlyIns;"),
            ascii("Lnet/minecraftforge/registries/ObjectHolder;"),
            ascii("Lnet/minecraftforge/eventbus/api/SubscribeEvent;")
    };
    private static final LongAdder BYPASSED = new LongAdder();
    private static final LongAdder PHASE_QUERIES = new LongAdder();
    private static final LongAdder MIXIN_PLUGIN_SKIPPED = new LongAdder();
    private static volatile Scope scope;
    private static volatile ClassLoader generatorLoader;
    private static final ClassValue<Boolean> TRUSTED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String expected = SUPPORTED.get(type.getName());
            String current = SUPPORTED_CURRENT.get(type.getName());
            if (expected == null && current == null) return false;
            try (InputStream input = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) {
                if (input == null) return false;
                String actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                return actual.equals(expected) || actual.equals(current);
            } catch (IOException | NoSuchAlgorithmException exception) {
                return false;
            }
        }
    };
    private static final ClassValue<MixinAccess> MIXIN_ACCESS = new ClassValue<>() {
        @Override
        protected MixinAccess computeValue(Class<?> type) {
            try {
                RuntimeModuleAccess.openToAgent(type);
                Class<?> owner = type.getName().equals(MIXIN) ? type.getSuperclass() : type;
                Field processors = owner.getDeclaredField("processors");
                processors.setAccessible(true);
                ClassLoader loader = type.getClassLoader();
                Class<?> tracker = Class.forName(TRACKER, false, loader);
                RuntimeModuleAccess.openToAgent(tracker);
                return new MixinAccess(processors, method(tracker, "processClass", 4), method(tracker, "handlesClass", 3));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("unsupported Mixin processor bridge", exception);
            }
        }
    };
    private static final ClassValue<Method> EVENT_ENGINE_ACCESS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            RuntimeModuleAccess.openToAgent(type);
            return method(type, "getEventBusEngine", 0);
        }
    };
    private static final ClassValue<Method> PENDING_WRAPPER = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Class<?> factory = Class.forName("net.minecraftforge.eventbus.ModLauncherFactory", false,
                        type.getClassLoader());
                RuntimeModuleAccess.openToAgent(factory);
                return method(factory, "hasPendingWrapperClass", 1);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };
    private static final ClassValue<Method> AUDIT = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            RuntimeModuleAccess.openToAgent(type);
            return method(type, "addReason", 2);
        }
    };

    private ClassPassThrough() {
    }

    public static void invalidate() {
        scope = null;
    }

    public static void generatorTrackingInstalled(ClassLoader loader) {
        generatorLoader = loader;
        MixinTargetIndex.invalidate();
    }

    static boolean publish(ClassLoader loader, Object environment, Set<String> targets,
            List<String> mixinPackages, Iterable<?> coprocessors, List<?> generators, long revision) throws ReflectiveOperationException {
        if (revision != MixinTargetIndex.revision() || loader != generatorLoader || generators.size() != 2) return false;
        Set<String> generatorTypes = new HashSet<>();
        for (Object generator : generators) {
            Class<?> type = generator.getClass();
            if ((!type.getName().equals(ARGS_GENERATOR) && !type.getName().equals(INNER_GENERATOR))
                    || !TRUSTED.get(type)) return false;
            generatorTypes.add(type.getName());
        }
        if (generatorTypes.size() != 2) return false;
        if (!"DEFAULT".equals(environment.getClass().getMethod("getPhase").invoke(environment).toString())) return false;
        Set<String> targetClasses = Set.copyOf(targets);
        Set<String> targetPackages = new HashSet<>();
        for (String target : targets) targetPackages.add(packageName(target));
        Prefixes prefixes = new Prefixes();
        for (String prefix : mixinPackages) if (prefix != null && !prefix.isEmpty()) prefixes.add(prefix);
        Set<String> specialClasses = new HashSet<>();
        for (Object coprocessor : coprocessors) {
            String fieldName = switch (coprocessor.getClass().getName()) {
                case "org.spongepowered.asm.mixin.transformer.MixinCoprocessorPassthrough" -> "loadable";
                case "org.spongepowered.asm.mixin.transformer.MixinCoprocessorAccessor" -> "accessorMixins";
                case "org.spongepowered.asm.mixin.transformer.MixinCoprocessorSyntheticInner" -> "syntheticInnerClasses";
                case "org.spongepowered.asm.mixin.transformer.MixinCoprocessorNestHost" -> "nestHosts";
                default -> null;
            };
            if (fieldName == null) return false;
            RuntimeModuleAccess.openToAgent(coprocessor.getClass());
            Field field = coprocessor.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object entries = field.get(coprocessor);
            Iterable<?> names = entries instanceof Map<?, ?> map ? map.keySet() : (Set<?>) entries;
            for (Object name : names) specialClasses.add(((String) name).replace('/', '.'));
        }
        Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", false, loader);
        RuntimeModuleAccess.openToAgent(mixins);
        Method unvisited = mixins.getMethod("getUnvisitedCount");
        unvisited.setAccessible(true);
        Scope published = new Scope(loader, environment, environment.getClass().getMethod("getCurrentEnvironment"),
                unvisited, targetClasses, Set.copyOf(targetPackages), prefixes, Set.copyOf(specialClasses), revision);
        if (revision != MixinTargetIndex.revision()) return false;
        scope = published;
        return true;
    }

    public static boolean transform(byte[] bytes, String name, String reason, Object asmType,
            Map<?, ?> phases, boolean hasTransformers, Object audit, ClassLoader loader) {
        Scope active = scope;
        if (hasTransformers || bytes.length == 0 || !"classloading".equals(reason) || active == null
                || !audit.getClass().getName().equals("cpw.mods.modlauncher.TransformerAuditTrail")
                || name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                || name.startsWith("net.neoforged.")
                || name.startsWith("com.mojang.") || !active.containsNoWork(name)) return false;

        Object mixin = null;
        Object mixinPhase = null;
        for (Map.Entry<?, ?> entry : phases.entrySet()) {
            for (Object plugin : (List<?>) entry.getValue()) {
                Class<?> type = plugin.getClass();
                if (!TRUSTED.get(type)) return false;
                if (type.getName().equals(MIXIN) || type.getName().equals(MIXIN_LEGACY)) {
                    if (type.getName().equals(MIXIN) && !TRUSTED.get(type.getSuperclass())) return false;
                    if (!((Enum<?>) entry.getKey()).name().equals("AFTER")) return false;
                    mixin = plugin;
                    mixinPhase = entry.getKey();
                } else if (type.getName().equals(EVENT_BUS)) {
                    Object engine = invoke(EVENT_ENGINE_ACCESS.get(type), plugin);
                    if (!engine.getClass().getName().equals(EVENT_ENGINE) || !TRUSTED.get(engine.getClass())
                            || (boolean) invoke(PENDING_WRAPPER.get(engine.getClass()), null, name)) return false;
                }
            }
        }
        if (mixin == null || mixin.getClass().getClassLoader() != active.loader) return false;
        ClassReader reader = new ClassReader(bytes);
        String parent = reader.getSuperName();
        if (!ClassHierarchy.excludesEvent(loader, parent)
                || "net/minecraftforge/common/capabilities/CapabilityToken".equals(parent)
                || (reader.getAccess() & Opcodes.ACC_ENUM) != 0
                    && Arrays.asList(reader.getInterfaces()).contains("net/minecraftforge/common/IExtensibleEnum")
                || hasTransformAnnotation(reader, bytes)) return false;

        MixinAccess access = MIXIN_ACCESS.get(mixin.getClass());
        Object tracker = classTracker(access, mixin);
        if (tracker == null || scope != active || active.revision != MixinTargetIndex.revision()
                || invoke(active.currentEnvironment, null) != active.environment) return false;
        // The two supported generators emit in target packages or asm.synthetic;
        // Scope excludes both. The tracker protects its own loaded-class set.
        invoke(AUDIT.get(audit.getClass()), audit, name, reason);
        invoke(access.trackClass, tracker, mixinPhase, null, asmType, reason);
        BYPASSED.increment();
        return true;
    }

    public static EnumSet<?> phases(Object plugin, Object asmType, boolean empty, String reason) {
        if (empty || "mixin".equals(reason)) return null;
        Class<?> type = plugin.getClass();
        if (!TRUSTED.get(type)
                || type.getName().equals(MIXIN) && !TRUSTED.get(type.getSuperclass())) return null;
        MixinAccess access = MIXIN_ACCESS.get(type);
        Object tracker = classTracker(access, plugin);
        if (tracker == null) return null;
        // For existing bytes the standard handler always selects AFTER, even
        // before Mixin prepares its target index. Only the tracker validates here.
        EnumSet<?> phases = (EnumSet<?>) invoke(access.handlesClass, tracker, asmType, false, reason);
        PHASE_QUERIES.increment();
        return phases.clone();
    }

    public static boolean skipMixinPlugin(Object plugin, Object phase, Object asmType, String name, String reason) {
        Scope active = scope;
        if (active == null || !"classloading".equals(reason)
                || !((Enum<?>) phase).name().equals("AFTER")
                || (!plugin.getClass().getName().equals(MIXIN) && !plugin.getClass().getName().equals(MIXIN_LEGACY))
                || plugin.getClass().getClassLoader() != active.loader
                || !TRUSTED.get(plugin.getClass())
                || plugin.getClass().getName().equals(MIXIN) && !TRUSTED.get(plugin.getClass().getSuperclass())
                || !active.containsNoWork(name)
                || scope != active || active.revision != MixinTargetIndex.revision()
                || invoke(active.currentEnvironment, null) != active.environment
                || (int) invoke(active.unvisited, null) != 0) return false;
        MixinAccess access = MIXIN_ACCESS.get(plugin.getClass());
        Object tracker = classTracker(access, plugin);
        if (tracker == null) return false;
        invoke(access.trackClass, tracker, phase, null, asmType, reason);
        MIXIN_PLUGIN_SKIPPED.increment();
        return true;
    }

    private static Object classTracker(MixinAccess access, Object plugin) {
        try {
            List<?> processors = (List<?>) access.processors.get(plugin);
            if (processors.size() != 2) return null;
            boolean handler = false;
            Object tracker = null;
            for (Object processor : processors) {
                if (!TRUSTED.get(processor.getClass())) return null;
                if (processor.getClass().getName().equals(HANDLER)) handler = true;
                else if (processor.getClass().getName().equals(TRACKER)) tracker = processor;
                else return null;
            }
            return handler ? tracker : null;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean hasTransformAnnotation(ClassReader reader, byte[] bytes) {
        boolean subscriberDescriptor = false;
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0 || reader.readByte(offset - 1) != 1) continue;
            int length = reader.readUnsignedShort(offset);
            for (int annotationIndex = 0; annotationIndex < ANNOTATIONS.length; annotationIndex++) {
                byte[] annotation = ANNOTATIONS[annotationIndex];
                if (annotation.length != length) continue;
                int index = 0;
                while (index < length && bytes[offset + 2 + index] == annotation[index]) index++;
                if (index == length) {
                    if (annotationIndex < 3) return true;
                    subscriberDescriptor = true;
                }
            }
        }
        if (!subscriberDescriptor) return false;
        boolean publicClass = (reader.getAccess() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                == Opcodes.ACC_PUBLIC;
        boolean[] changesAccess = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (visible && annotation.equals("Lnet/minecraftforge/eventbus/api/SubscribeEvent;")
                                && (!publicClass || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                                != Opcodes.ACC_PUBLIC)) changesAccess[0] = true;
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return changesAccess[0];
    }

    private static Method method(Class<?> type, String name, int parameters) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("missing " + type.getName() + '.' + name);
    }

    private static Object invoke(Method method, Object instance, Object... arguments) {
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String packageName(String name) {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    public static long bypassed() { return BYPASSED.sum(); }
    public static long phaseQueries() { return PHASE_QUERIES.sum(); }
    public static long mixinPluginSkipped() { return MIXIN_PLUGIN_SKIPPED.sum(); }

    private record MixinAccess(Field processors, Method trackClass, Method handlesClass) { }

    private record Scope(ClassLoader loader, Object environment, Method currentEnvironment, Method unvisited,
                         Set<String> targetClasses, Set<String> targetPackages, Prefixes mixinPackages,
                         Set<String> specialClasses, long revision) {
        private boolean containsNoWork(String name) {
            return !name.startsWith("org.spongepowered.asm.synthetic.")
                    && !targetClasses.contains(name)
                    && (name.indexOf('$') < 0 || !targetPackages.contains(packageName(name)))
                    && !mixinPackages.containsPrefixOf(name)
                    && !specialClasses.contains(name);
        }
    }

    private static final class Prefixes {
        private final Map<Character, Prefixes> children = new HashMap<>();
        private boolean terminal;

        private void add(String value) {
            Prefixes node = this;
            for (int i = 0; i < value.length(); i++) node = node.children.computeIfAbsent(value.charAt(i), ignored -> new Prefixes());
            node.terminal = true;
        }

        private boolean containsPrefixOf(String value) {
            Prefixes node = this;
            for (int i = 0; i < value.length(); i++) {
                node = node.children.get(value.charAt(i));
                if (node == null) return false;
                if (node.terminal) return true;
            }
            return false;
        }
    }
}

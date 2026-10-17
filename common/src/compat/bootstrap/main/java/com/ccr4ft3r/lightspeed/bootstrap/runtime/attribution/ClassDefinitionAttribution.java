package com.ccr4ft3r.lightspeed.bootstrap.runtime.attribution;

import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ClassDefinitionAttribution {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("lightspeed.agent.attribution", "false"));
    private static final int DEFAULT_MAX_GROUPS = 256;
    private static final int MAX_GROUPS = boundedIntegerProperty(
            "lightspeed.agent.attribution.maxGroups", DEFAULT_MAX_GROUPS, 4, 1024);
    private static final int SUMMARY_LIMIT = boundedIntegerProperty(
            "lightspeed.agent.attribution.summaryLimit", 32, 1, 128);
    private static final ConcurrentHashMap<GroupKey, GroupCounters> GROUPS = new ConcurrentHashMap<>();
    private static final LongAdder OVERFLOW = new LongAdder();
    private static final LongAdder OVERFLOW_EVENT_BUS_WRAPPER_LIKE = new LongAdder();
    private static final ThreadLocal<SourceCache> LAST_SOURCE = ThreadLocal.withInitial(SourceCache::new);
    private static volatile boolean active = ENABLED;
    private static volatile boolean groupLimitReached;

    private ClassDefinitionAttribution() {
    }

    public static void initialize() {
        if (!ENABLED) {
            return;
        }
        // Force all objects used on the transform callback's fast path to initialize before registration.
        new GroupKey("", "", "");
        new GroupCounters();
        LAST_SOURCE.get();
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void record(Module module, ClassLoader loader, String className,
                              ProtectionDomain protectionDomain) {
        if (!active || loader == null) {
            return;
        }
        boolean wrapperLike = isEventBusWrapperLike(className);

        GroupKey key = new GroupKey(moduleName(module), loaderName(loader), source(protectionDomain));
        GroupCounters counters = GROUPS.get(key);
        if (counters == null) {
            if (groupLimitReached) {
                recordOverflow(wrapperLike);
                return;
            }
            synchronized (GROUPS) {
                counters = GROUPS.get(key);
                if (counters == null) {
                    if (GROUPS.size() >= MAX_GROUPS) {
                        groupLimitReached = true;
                        recordOverflow(wrapperLike);
                        return;
                    }
                    counters = new GroupCounters();
                    GROUPS.put(key, counters);
                }
            }
        }
        counters.classes.increment();
        if (wrapperLike) {
            counters.eventBusWrapperLike.increment();
        }
    }

    public static long total() {
        return OVERFLOW.sum() + GROUPS.values().stream().mapToLong(value -> value.classes.sum()).sum();
    }

    public static int groupCount() {
        return GROUPS.size();
    }

    public static long overflow() {
        return OVERFLOW.sum();
    }

    public static long eventBusWrapperLike() {
        return OVERFLOW_EVENT_BUS_WRAPPER_LIKE.sum()
                + GROUPS.values().stream().mapToLong(value -> value.eventBusWrapperLike.sum()).sum();
    }

    public static List<Summary> summaries() {
        List<Summary> summaries = new ArrayList<>(GROUPS.size());
        GROUPS.forEach((key, value) -> summaries.add(new Summary(
                key.module(), key.loader(), key.source(), value.classes.sum(),
                value.eventBusWrapperLike.sum())));
        summaries.sort(Comparator.comparingLong(Summary::eventBusWrapperLike).reversed()
                .thenComparing(Comparator.comparingLong(Summary::classes).reversed())
                .thenComparing(Summary::module)
                .thenComparing(Summary::loader)
                .thenComparing(Summary::source));
        return List.copyOf(summaries);
    }

    public static Snapshot stopAndSnapshot() {
        active = false;
        List<Summary> summaries = summaries();
        long overflow = OVERFLOW.sum();
        long classes = overflow + summaries.stream().mapToLong(Summary::classes).sum();
        long wrapperLike = OVERFLOW_EVENT_BUS_WRAPPER_LIKE.sum()
                + summaries.stream().mapToLong(Summary::eventBusWrapperLike).sum();
        return new Snapshot(classes, summaries.size(), overflow, wrapperLike, summaries);
    }

    public static void printSummaryGroups(Snapshot snapshot) {
        int limit = Math.min(SUMMARY_LIMIT, snapshot.summaries().size());
        for (int index = 0; index < limit; index++) {
            Summary summary = snapshot.summaries().get(index);
            System.err.println("[Lightspeed Agent] attribution classes=" + summary.classes()
                    + " eventBusWrapperLike=" + summary.eventBusWrapperLike()
                    + " module=" + summary.module()
                    + " loader=" + summary.loader()
                    + " source=" + summary.source());
        }
        if (snapshot.summaries().size() > limit) {
            System.err.println("[Lightspeed Agent] attribution groupsOmitted="
                    + (snapshot.summaries().size() - limit));
        }
    }

    private static String moduleName(Module module) {
        if (module == null) {
            return "<unknown>";
        }
        return module.isNamed() ? module.getName() : "<unnamed>";
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "<bootstrap>" : loader.getClass().getName();
    }

    private static String source(ProtectionDomain domain) {
        if (domain == null) {
            return "<unknown>";
        }
        SourceCache cache = LAST_SOURCE.get();
        if (cache.domain == domain) {
            return cache.source;
        }
        String source = "<unknown>";
        try {
            CodeSource codeSource = domain.getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            if (location != null) {
                source = sanitize(location.toExternalForm());
            }
        } catch (SecurityException exception) {
            source = "<restricted>";
        }
        cache.domain = domain;
        cache.source = source;
        return source;
    }

    private static boolean isEventBusWrapperLike(String className) {
        if (className == null) {
            return false;
        }
        int separator = Math.max(className.lastIndexOf('/'), className.lastIndexOf('.'));
        String simpleName = className.substring(separator + 1);
        return simpleName.startsWith("__") && simpleName.indexOf('_', 2) > 2;
    }

    private static void recordOverflow(boolean wrapperLike) {
        OVERFLOW.increment();
        if (wrapperLike) {
            OVERFLOW_EVENT_BUS_WRAPPER_LIKE.increment();
        }
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private static int boundedIntegerProperty(String name, int defaultValue, int minimum, int maximum) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public record Summary(String module, String loader, String source, long classes,
                          long eventBusWrapperLike) {
    }

    public record Snapshot(long classes, int groups, long overflow, long eventBusWrapperLike,
                           List<Summary> summaries) {
    }

    private record GroupKey(String module, String loader, String source) {
    }

    private static final class GroupCounters {
        private final LongAdder classes = new LongAdder();
        private final LongAdder eventBusWrapperLike = new LongAdder();
    }

    private static final class SourceCache {
        private ProtectionDomain domain;
        private String source;
    }
}

package com.ccr4ft3r.lightspeed.bootstrap.compiler;

import com.ccr4ft3r.lightspeed.bootstrap.LightspeedAgent;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.index.ResourceMembershipIndex;
import com.ccr4ft3r.lightspeed.bootstrap.runtime.scan.ScanMetadataCache;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ScanPackCompiler {
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_PATH = 3;
    public static final int EXIT_GLOBAL_FAILURE = 4;
    private static final int MAX_JARS = 10_000;

    private ScanPackCompiler() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        long started = System.nanoTime();
        String expectedAgentDigest = System.getProperty("lightspeed.packCompiler.agentDigest");
        if (expectedAgentDigest != null && !LightspeedAgent.ownerMatchesAgent(expectedAgentDigest)) {
            output.println("PACK_COMPILER_INERT owner-or-agent-mismatch");
            return 0;
        }
        Arguments arguments = Arguments.parse(args);
        if (arguments == null) {
            error.println("Usage: ScanPackCompiler --game-dir <path> --cache-dir <path>");
            return EXIT_USAGE;
        }

        try {
            Path gameDirectory = existingDirectory(arguments.gameDirectory());
            Path modsDirectory = existingDirectory(gameDirectory.resolve("mods"));
            if (!modsDirectory.getParent().equals(gameDirectory)) {
                error.println("Invalid mods path");
                return EXIT_PATH;
            }
            Path cacheDirectory = cacheDirectory(arguments.cacheDirectory());
            System.setProperty("lightspeed.bootstrapCacheDir", cacheDirectory.toString());
            if (!ScanMetadataCache.standaloneAvailable()) {
                error.println("Scan metadata cache is disabled");
                return EXIT_GLOBAL_FAILURE;
            }

            List<Path> jars = listJars(modsDirectory);
            Optional<ScanCompilerStamp.Stats> current = ScanCompilerStamp.current(cacheDirectory, jars);
            if (current.isPresent()) {
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
                output.println("PACK_COMPILER_OK jars=" + jars.size() + " compiled=0 reused="
                        + current.orElseThrow().supported() + " skipped=" + current.orElseThrow().skipped()
                        + " classes=0 timeMs=" + elapsedMillis);
                return 0;
            }
            int skipped = Math.max(0, jars.size() - MAX_JARS);
            List<Path> boundedJars = jars.size() > MAX_JARS ? jars.subList(0, MAX_JARS) : jars;
            int reused = 0;
            List<Candidate> candidates = new ArrayList<>();
            for (Path jar : boundedJars) {
                String key = ResourceMembershipIndex.physicalJarScanKey(jar);
                if (key == null) {
                    skipped++;
                } else if (ScanMetadataCache.standaloneContains(key)) {
                    reused++;
                } else {
                    candidates.add(new Candidate(jar, key));
                }
            }

            ForgeScanRuntime runtime = candidates.isEmpty() ? null : ForgeScanRuntime.load();
            int compiled = 0;
            int classes = 0;
            int configuredWorkers = Integer.getInteger("lightspeed.packCompiler.workers",
                    defaultWorkerCount(Runtime.getRuntime().availableProcessors()));
            int workers = Math.max(1, Math.min(2, configuredWorkers));
            ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "Lightspeed-Scan-Pack-Compiler");
                thread.setDaemon(true);
                return thread;
            });
            List<Future<PhysicalModScanner.Result>> futures = new ArrayList<>(candidates.size());
            try {
                for (Candidate candidate : candidates) {
                    futures.add(executor.submit(() -> PhysicalModScanner.scan(
                            candidate.path(), candidate.key(), runtime)));
                }
                for (int index = 0; index < futures.size(); index++) {
                    Future<PhysicalModScanner.Result> future = futures.set(index, null);
                    PhysicalModScanner.Result result;
                    try {
                        result = future.get();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("scan compilation interrupted", exception);
                    } catch (ExecutionException exception) {
                        skipped++;
                        continue;
                    }
                    if (!result.supported()
                            || !result.key().equals(ResourceMembershipIndex.physicalJarScanKey(result.path()))
                            || !ScanMetadataCache.standaloneRecord(result.key(), result.encoded())) {
                        skipped++;
                        continue;
                    }
                    compiled++;
                    classes += result.classes();
                }
            } finally {
                futures.stream().filter(java.util.Objects::nonNull).forEach(future -> future.cancel(true));
                executor.shutdownNow();
            }

            if (!ScanMetadataCache.standalonePersist()) {
                error.println("Failed to publish scan PackImage");
                return EXIT_GLOBAL_FAILURE;
            }
            ScanCompilerStamp.write(cacheDirectory, jars, compiled + reused, skipped);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            output.println("PACK_COMPILER_OK jars=" + jars.size() + " compiled=" + compiled + " reused=" + reused
                    + " skipped=" + skipped + " classes=" + classes + " timeMs=" + elapsedMillis);
            return 0;
        } catch (InvalidPathException exception) {
            error.println("Invalid path: " + exception.getMessage());
            return EXIT_PATH;
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            error.println("Scan Pack Compiler failed: " + conciseFailure(exception));
            return EXIT_GLOBAL_FAILURE;
        }
    }

    static int defaultWorkerCount(int processors) {
        return processors >= 8 ? 2 : 1;
    }

    private static String conciseFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() > 160) {
            singleLine = singleLine.substring(0, 160);
        }
        return current.getClass().getSimpleName() + ": " + singleLine;
    }

    private static Path existingDirectory(Path path) throws IOException, InvalidPathException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidPathException(normalized.toString());
        }
        return normalized.toRealPath();
    }

    private static Path cacheDirectory(Path path) throws IOException, InvalidPathException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))) {
            throw new InvalidPathException(normalized.toString());
        }
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized)) {
            throw new InvalidPathException(normalized.toString());
        }
        return normalized.toRealPath();
    }

    private static List<Path> listJars(Path modsDirectory) throws IOException {
        try (var paths = Files.list(modsDirectory)) {
            return paths.filter(path -> path.getParent().equals(modsDirectory))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing((Path path) ->
                                    path.getFileName().toString().toLowerCase(Locale.ROOT))
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private record Candidate(Path path, String key) {
    }

    private record Arguments(Path gameDirectory, Path cacheDirectory) {
        private static Arguments parse(String[] args) {
            if (args == null || args.length != 4) {
                return null;
            }
            Path gameDirectory = null;
            Path cacheDirectory = null;
            try {
                for (int index = 0; index < args.length; index += 2) {
                    if ("--game-dir".equals(args[index]) && gameDirectory == null) {
                        gameDirectory = Path.of(args[index + 1]);
                    } else if ("--cache-dir".equals(args[index]) && cacheDirectory == null) {
                        cacheDirectory = Path.of(args[index + 1]);
                    } else {
                        return null;
                    }
                }
            } catch (java.nio.file.InvalidPathException exception) {
                return null;
            }
            return gameDirectory == null || cacheDirectory == null
                    ? null
                    : new Arguments(gameDirectory, cacheDirectory);
        }
    }

    private static final class InvalidPathException extends Exception {
        private InvalidPathException(String path) {
            super(path);
        }
    }
}

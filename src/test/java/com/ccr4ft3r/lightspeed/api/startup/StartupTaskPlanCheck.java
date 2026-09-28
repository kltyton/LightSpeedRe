package com.ccr4ft3r.lightspeed.api.startup;

import com.ccr4ft3r.lightspeed.startup.tasks.StartupTaskPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class StartupTaskPlanCheck {
    private static final long TIMEOUT_SECONDS = 2L;

    private StartupTaskPlanCheck() {
    }

    public static void main(String[] args) throws Exception {
        try {
            checkValidation();
            checkDirectConstructionBounds();
            checkDuplicateFreezeAndReset();
            checkFreezeValidationBeforeFrozen();
            checkConcurrentRegisterFreezeLinearizability();
            checkUnknownDependency();
            checkCycle();
            checkContradictoryEffectAfterCycle();
            checkReadWriteRegistrationOrder();
            checkSameTaskReadWrite();
            checkTransitiveConflictSequence();
            checkExplicitAfter();
            checkLegacyOrder();
            checkIndependentParallelStart();
            checkSequentialSingleUseExecution();
            checkConcurrentSingleUseExecution();
            checkInvalidExecutorsDoNotConsumePlan();
            checkExecutorRejectionConsumesPlan();
            checkExecutorKindAffinity();
            checkFailureBlocksDependents();
            checkCriticalPathNanos();
            System.out.println("STARTUP_TASK_PLAN_OK");
        } finally {
            StartupTasks.resetForTests();
        }
    }

    private static void checkValidation() {
        expectThrows(IllegalArgumentException.class, "invalid startup task id",
                () -> task("invalid", StartupTask.Kind.CPU_PURE, () -> {
                }));
        expectThrows(IllegalArgumentException.class, "startup task is incomplete",
                () -> task("check:null-kind", null, () -> {
                }));
        expectThrows(IllegalArgumentException.class, "startup task is incomplete",
                () -> task("check:null-action", StartupTask.Kind.CPU_PURE, null));
        expectThrows(IllegalArgumentException.class, "startup task is incomplete",
                () -> StartupTask.builder("check:negative", StartupTask.Kind.CPU_PURE, () -> {
                }).estimatedNanos(-1L).build());
        expectThrows(IllegalArgumentException.class, "invalid startup task reads",
                () -> StartupTask.builder("check:blank-read", StartupTask.Kind.CPU_PURE, () -> {
                }).reads(Set.of(" ")).build());
        Set<String> tooManyEffects = IntStream.rangeClosed(0, 1024)
                .mapToObj(index -> "effect-" + index)
                .collect(Collectors.toSet());
        expectThrows(IllegalArgumentException.class, "invalid startup task writes",
                () -> StartupTask.builder("check:too-many", StartupTask.Kind.CPU_PURE, () -> {
                }).writes(tooManyEffects).build());
        expectThrows(IllegalArgumentException.class, "must be deterministic",
                () -> StartupTask.builder("check:cacheable", StartupTask.Kind.CPU_PURE, () -> {
                }).cacheable(true).build());

        StartupTask defaults = StartupTask.builder("check:defaults", StartupTask.Kind.CPU_PURE, () -> {
        }).reads(null).writes(null).after(null).build();
        require(defaults.reads().isEmpty() && defaults.writes().isEmpty() && defaults.after().isEmpty(),
                "null effect sets were not normalized to empty sets");
        expectThrows(IllegalArgumentException.class, "executors are incomplete",
                () -> new StartupTaskPlan.Executors(Runnable::run, Runnable::run, Runnable::run,
                        Runnable::run, Runnable::run, Runnable::run, null));
    }

    private static void checkDirectConstructionBounds() {
        expectThrows(IllegalArgumentException.class, "list must not be null",
                () -> StartupTaskPlan.create(null));
        expectThrows(IllegalArgumentException.class, "entry must not be null",
                () -> StartupTaskPlan.create(Collections.singletonList(null)));

        StartupTask repeated = task("construction:repeated", StartupTask.Kind.CPU_PURE, () -> {
        });
        expectThrows(IllegalArgumentException.class, "task limit exceeded",
                () -> StartupTaskPlan.create(Collections.nCopies(4097, repeated)));

        List<StartupTask> maximum = IntStream.range(0, 4096)
                .mapToObj(index -> task("construction:task-" + index, StartupTask.Kind.CPU_PURE, () -> {
                }))
                .toList();
        require(StartupTaskPlan.create(maximum).order().size() == 4096,
                "maximum-size startup task plan was rejected");
    }

    private static void checkDuplicateFreezeAndReset() {
        StartupTasks.resetForTests();
        StartupTask first = task("registry:first", StartupTask.Kind.CPU_PURE, () -> {
        });
        StartupTasks.register(first);
        expectThrows(IllegalArgumentException.class, "duplicate startup task",
                () -> StartupTasks.register(first));
        StartupTaskPlan firstPlan = StartupTasks.freeze();
        require(firstPlan.order().equals(List.of("registry:first")),
                "freeze did not retain the registered task");
        require(StartupTasks.freeze() == firstPlan,
                "repeated freeze did not return the cached plan instance");
        expectThrows(IllegalStateException.class, "registration is frozen",
                () -> StartupTasks.register(task("registry:late", StartupTask.Kind.CPU_PURE, () -> {
                })));

        StartupTasks.resetForTests();
        StartupTasks.register(first);
        StartupTaskPlan resetPlan = StartupTasks.freeze();
        require(resetPlan.order().equals(List.of("registry:first")),
                "reset did not clear frozen registry state");
        require(resetPlan != firstPlan, "reset did not clear the cached plan");
    }

    private static void checkFreezeValidationBeforeFrozen() {
        StartupTasks.resetForTests();
        try {
            StartupTasks.register(configuredTask("freeze:invalid", StartupTask.Kind.CPU_PURE,
                    Set.of(), Set.of(), Set.of("freeze:missing"), 0L, () -> {
                    }));
            expectThrows(IllegalArgumentException.class, "unknown startup dependency freeze:missing",
                    StartupTasks::freeze);
            StartupTasks.register(task("freeze:still-open", StartupTask.Kind.CPU_PURE, () -> {
            }));
        } finally {
            StartupTasks.resetForTests();
        }
    }

    private static void checkConcurrentRegisterFreezeLinearizability() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                StartupTasks.resetForTests();
                StartupTask candidate = task("linear:task-" + round, StartupTask.Kind.CPU_PURE, () -> {
                });
                CountDownLatch start = new CountDownLatch(1);
                Future<Throwable> registration = callers.submit(() -> {
                    awaitLatch(start, "linearizable registration start");
                    try {
                        StartupTasks.register(candidate);
                        return null;
                    } catch (Throwable throwable) {
                        return throwable;
                    }
                });
                Future<StartupTaskPlan> freezing = callers.submit(() -> {
                    awaitLatch(start, "linearizable freeze start");
                    return StartupTasks.freeze();
                });
                start.countDown();

                Throwable registrationFailure = registration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                StartupTaskPlan plan = freezing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (registrationFailure == null) {
                    require(plan.order().equals(List.of(candidate.id())),
                            "successful concurrent registration was absent from the frozen plan");
                } else {
                    require(registrationFailure instanceof IllegalStateException,
                            "concurrent registration failed unexpectedly: " + registrationFailure);
                    require(plan.order().isEmpty(),
                            "rejected concurrent registration appeared in the frozen plan");
                }
                require(StartupTasks.freeze() == plan,
                        "concurrent freeze did not publish one stable plan instance");
            }
        } finally {
            StartupTasks.resetForTests();
            shutdown(callers);
        }
    }

    private static void checkUnknownDependency() {
        StartupTask dependent = configuredTask("dependency:dependent", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of("dependency:missing"), 0L, () -> {
                });
        expectThrows(IllegalArgumentException.class, "unknown startup dependency dependency:missing",
                () -> StartupTaskPlan.create(List.of(dependent)));
    }

    private static void checkCycle() {
        StartupTask first = configuredTask("cycle:first", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of("cycle:second"), 0L, () -> {
                });
        StartupTask second = configuredTask("cycle:second", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of("cycle:first"), 0L, () -> {
                });
        expectThrows(IllegalArgumentException.class, "startup task dependency cycle",
                () -> StartupTaskPlan.create(List.of(first, second)));
    }

    private static void checkContradictoryEffectAfterCycle() {
        StartupTask first = configuredTask("effect-cycle:first", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of("effect-cycle:resource"), Set.of("effect-cycle:second"), 0L, () -> {
                });
        StartupTask second = configuredTask("effect-cycle:second", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of("effect-cycle:resource"), Set.of(), 0L, () -> {
                });
        expectThrows(IllegalArgumentException.class, "startup task dependency cycle",
                () -> StartupTaskPlan.create(List.of(first, second)));
    }

    private static void checkReadWriteRegistrationOrder() throws Exception {
        AtomicBoolean writeBeforeRead = new AtomicBoolean();
        AtomicBoolean readBeforeWrite = new AtomicBoolean();
        AtomicBoolean writeBeforeWrite = new AtomicBoolean();
        List<StartupTask> tasks = List.of(
                configuredTask("conflict:write-read-first", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of("resource:write-read"), Set.of(), 0L,
                        () -> writeBeforeRead.set(true)),
                configuredTask("conflict:write-read-second", StartupTask.Kind.CPU_PURE,
                        Set.of("resource:write-read"), Set.of(), Set.of(), 0L,
                        () -> require(writeBeforeRead.get(), "reader ran before its registered writer")),
                configuredTask("conflict:read-write-first", StartupTask.Kind.CPU_PURE,
                        Set.of("resource:read-write"), Set.of(), Set.of(), 0L,
                        () -> readBeforeWrite.set(true)),
                configuredTask("conflict:read-write-second", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of("resource:read-write"), Set.of(), 0L,
                        () -> require(readBeforeWrite.get(), "writer ran before its registered reader")),
                configuredTask("conflict:write-write-first", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of("resource:write-write"), Set.of(), 0L,
                        () -> writeBeforeWrite.set(true)),
                configuredTask("conflict:write-write-second", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of("resource:write-write"), Set.of(), 0L,
                        () -> require(writeBeforeWrite.get(), "second writer ran before the first writer")));

        ManualExecutor executor = new ManualExecutor();
        CompletableFuture<Void> result = StartupTaskPlan.create(tasks).execute(allKinds(executor));
        require(executor.size() == 3, "conflicting successors were scheduled before their predecessors");
        executor.runExactly(3);
        require(writeBeforeRead.get() && readBeforeWrite.get() && writeBeforeWrite.get(),
                "registered conflict roots did not all run first");
        require(executor.size() == 3, "conflicting successors were not released after predecessors completed");
        executor.runAll();
        await(result);
    }

    private static void checkSameTaskReadWrite() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(configuredTask(
                "same-effect:read-write", StartupTask.Kind.CPU_PURE,
                Set.of("same-effect:resource"), Set.of("same-effect:resource"), Set.of(), 0L,
                () -> ran.set(true))));
        require(plan.order().equals(List.of("same-effect:read-write")),
                "same-task read/write created a self-cycle");
        await(plan.execute(allKinds(Runnable::run)));
        require(ran.get(), "same-task read/write task did not execute");
    }

    private static void checkTransitiveConflictSequence() throws Exception {
        AtomicBoolean rootRan = new AtomicBoolean();
        AtomicInteger readersRan = new AtomicInteger();
        AtomicBoolean secondWriterRan = new AtomicBoolean();
        List<StartupTask> tasks = List.of(
                configuredTask("transitive:first-writer", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of("transitive:resource"), Set.of(), 0L,
                        () -> rootRan.set(true)),
                configuredTask("transitive:first-reader", StartupTask.Kind.CPU_PURE,
                        Set.of("transitive:resource"), Set.of(), Set.of(), 0L,
                        () -> {
                            require(rootRan.get(), "first reader ran before the prior writer");
                            readersRan.incrementAndGet();
                        }),
                configuredTask("transitive:second-reader", StartupTask.Kind.CPU_PURE,
                        Set.of("transitive:resource"), Set.of(), Set.of(), 0L,
                        () -> {
                            require(rootRan.get(), "second reader ran before the prior writer");
                            readersRan.incrementAndGet();
                        }),
                configuredTask("transitive:second-writer", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of("transitive:resource"), Set.of(), 0L,
                        () -> {
                            require(readersRan.get() == 2, "writer ran before all intervening readers");
                            secondWriterRan.set(true);
                        }),
                configuredTask("transitive:final-reader", StartupTask.Kind.CPU_PURE,
                        Set.of("transitive:resource"), Set.of(), Set.of(), 0L,
                        () -> require(secondWriterRan.get(), "final reader ran before the latest writer")));

        ManualExecutor executor = new ManualExecutor();
        CompletableFuture<Void> result = StartupTaskPlan.create(tasks).execute(allKinds(executor));
        require(executor.size() == 1, "transitive conflict root was not scheduled alone");
        executor.runExactly(1);
        require(executor.size() == 2, "readers since the last write were not released together");
        executor.runExactly(2);
        require(executor.size() == 1, "next writer did not wait for every intervening reader");
        executor.runExactly(1);
        require(executor.size() == 1, "final reader did not wait for the latest writer");
        executor.runAll();
        await(result);
    }

    private static void checkExplicitAfter() throws Exception {
        AtomicBoolean prerequisiteRan = new AtomicBoolean();
        StartupTask dependent = configuredTask("after:dependent", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of("after:prerequisite"), 0L,
                () -> require(prerequisiteRan.get(), "explicit dependent ran before prerequisite"));
        StartupTask prerequisite = task("after:prerequisite", StartupTask.Kind.CPU_PURE,
                () -> prerequisiteRan.set(true));
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(dependent, prerequisite));
        require(plan.order().equals(List.of("after:prerequisite", "after:dependent")),
                "explicit after dependency did not override registration order");

        ManualExecutor executor = new ManualExecutor();
        CompletableFuture<Void> result = plan.execute(allKinds(executor));
        require(executor.size() == 1, "explicit dependent was scheduled before prerequisite completion");
        executor.runAll();
        await(result);
    }

    private static void checkLegacyOrder() throws Exception {
        List<String> observed = Collections.synchronizedList(new ArrayList<>());
        StartupTask first = task("legacy:first", StartupTask.Kind.LEGACY, () -> observed.add("first"));
        StartupTask unrelated = task("legacy:unrelated", StartupTask.Kind.CPU_PURE, () -> {
        });
        StartupTask second = task("legacy:second", StartupTask.Kind.LEGACY, () -> observed.add("second"));
        StartupTask third = task("legacy:third", StartupTask.Kind.LEGACY, () -> observed.add("third"));
        ManualExecutor cpu = new ManualExecutor();
        ManualExecutor legacy = new ManualExecutor();
        StartupTaskPlan.Executors executors = new StartupTaskPlan.Executors(
                cpu, cpu, cpu, cpu, cpu, cpu, legacy);
        CompletableFuture<Void> result = StartupTaskPlan.create(List.of(first, unrelated, second, third))
                .execute(executors);
        require(legacy.size() == 1, "more than the first legacy task was initially scheduled");
        legacy.runExactly(1);
        require(legacy.size() == 1, "second legacy task was not released alone");
        legacy.runExactly(1);
        require(legacy.size() == 1, "third legacy task was not released alone");
        legacy.runExactly(1);
        cpu.runAll();
        await(result);
        require(observed.equals(List.of("first", "second", "third")),
                "legacy tasks did not preserve registration order: " + observed);
    }

    private static void checkIndependentParallelStart() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable action = () -> {
            started.countDown();
            awaitLatch(release, "parallel task release");
        };
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(
                task("parallel:first", StartupTask.Kind.CPU_PURE, action),
                task("parallel:second", StartupTask.Kind.CPU_PURE, action)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> result = plan.execute(allKinds(pool));
            require(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "independent tasks did not start concurrently");
            release.countDown();
            await(result);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    private static void checkSequentialSingleUseExecution() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(
                task("single-use:sequential", StartupTask.Kind.CPU_PURE, runs::incrementAndGet)));
        await(plan.execute(allKinds(Runnable::run)));
        expectThrows(IllegalStateException.class, "already been executed",
                () -> plan.execute(allKinds(Runnable::run)));
        require(runs.get() == 1, "sequential second execution reran the task");
    }

    private static void checkConcurrentSingleUseExecution() throws Exception {
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(
                task("single-use:concurrent", StartupTask.Kind.CPU_PURE, () -> {
                })));
        ManualExecutor taskExecutor = new ManualExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<CompletableFuture<Void>> acceptedResult = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Runnable caller = () -> {
            awaitLatch(start, "concurrent execute start");
            try {
                CompletableFuture<Void> result = plan.execute(allKinds(taskExecutor));
                accepted.incrementAndGet();
                acceptedResult.set(result);
            } catch (IllegalStateException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("already been executed")) {
                    rejected.incrementAndGet();
                } else {
                    unexpected.compareAndSet(null, exception);
                }
            } catch (Throwable throwable) {
                unexpected.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        };
        try {
            callers.execute(caller);
            callers.execute(caller);
            start.countDown();
            require(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "concurrent execute calls did not finish");
            require(unexpected.get() == null, "concurrent execution failed unexpectedly: " + unexpected.get());
            require(accepted.get() == 1 && rejected.get() == 1,
                    "concurrent execution was not single-use: accepted=" + accepted + ", rejected=" + rejected);
            require(taskExecutor.size() == 1, "concurrent execution scheduled the task more than once");
            taskExecutor.runAll();
            await(acceptedResult.get());
        } finally {
            start.countDown();
            shutdown(callers);
        }
    }

    private static void checkInvalidExecutorsDoNotConsumePlan() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(
                task("executor-validation:task", StartupTask.Kind.CPU_PURE, () -> ran.set(true))));
        expectThrows(IllegalArgumentException.class, "executors are incomplete",
                () -> plan.execute(null));
        await(plan.execute(allKinds(Runnable::run)));
        require(ran.get(), "invalid executors consumed the startup task plan");
    }

    private static void checkExecutorRejectionConsumesPlan() {
        AtomicBoolean ran = new AtomicBoolean();
        StartupTaskPlan plan = StartupTaskPlan.create(List.of(
                task("executor-rejection:task", StartupTask.Kind.CPU_PURE, () -> ran.set(true))));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("expected startup task rejection");
        };
        CompletableFuture<Void> rejected = plan.execute(allKinds(rejecting));
        ExecutionException failure = expectThrows(ExecutionException.class, null,
                () -> rejected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        require(failure.getCause() instanceof RejectedExecutionException
                        && failure.getCause().getMessage().contains("expected startup task rejection"),
                "executor rejection was not preserved as the future failure: " + failure.getCause());
        expectThrows(IllegalStateException.class, "already been executed",
                () -> plan.execute(allKinds(Runnable::run)));
        require(!ran.get(), "rejected executor ran the task");
    }

    private static void checkExecutorKindAffinity() throws Exception {
        Map<StartupTask.Kind, ExecutorService> pools = new EnumMap<>(StartupTask.Kind.class);
        Map<StartupTask.Kind, String> observedThreads = new ConcurrentHashMap<>();
        try {
            for (StartupTask.Kind kind : StartupTask.Kind.values()) {
                String threadName = "startup-kind-" + kind.name();
                pools.put(kind, Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                }));
            }
            List<StartupTask> tasks = new ArrayList<>();
            for (StartupTask.Kind kind : StartupTask.Kind.values()) {
                tasks.add(task("kind:" + kind.name().toLowerCase().replace('_', '-'), kind,
                        () -> observedThreads.put(kind, Thread.currentThread().getName())));
            }
            StartupTaskPlan.Executors executors = executorsFrom(pools);
            await(StartupTaskPlan.create(tasks).execute(executors));
            for (StartupTask.Kind kind : StartupTask.Kind.values()) {
                require(("startup-kind-" + kind.name()).equals(observedThreads.get(kind)),
                        kind + " ran on the wrong executor: " + observedThreads.get(kind));
            }
        } finally {
            for (ExecutorService pool : pools.values()) {
                shutdown(pool);
            }
        }
    }

    private static void checkFailureBlocksDependents() {
        AtomicBoolean dependentRan = new AtomicBoolean();
        StartupTask failure = task("failure:root", StartupTask.Kind.CPU_PURE,
                () -> {
                    throw new IllegalStateException("expected startup task failure");
                });
        StartupTask dependent = configuredTask("failure:dependent", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of("failure:root"), 0L,
                () -> dependentRan.set(true));
        ManualExecutor executor = new ManualExecutor();
        CompletableFuture<Void> result = StartupTaskPlan.create(List.of(failure, dependent))
                .execute(allKinds(executor));
        require(executor.size() == 1, "failed root was not the only initially scheduled task");
        executor.runAll();
        expectThrows(ExecutionException.class, null,
                () -> result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        require(!dependentRan.get(), "dependent ran after prerequisite failure");
        require(executor.size() == 0, "failed prerequisite scheduled its dependent");
    }

    private static void checkCriticalPathNanos() {
        List<StartupTask> tasks = List.of(
                configuredTask("critical:root", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of(), Set.of(), 3L, () -> {
                        }),
                configuredTask("critical:short", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of(), Set.of("critical:root"), 5L, () -> {
                        }),
                configuredTask("critical:long", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of(), Set.of("critical:root"), 7L, () -> {
                        }),
                configuredTask("critical:join", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of(), Set.of("critical:short", "critical:long"), 13L, () -> {
                        }),
                configuredTask("critical:independent", StartupTask.Kind.CPU_PURE,
                        Set.of(), Set.of(), Set.of(), 11L, () -> {
                        }));
        require(StartupTaskPlan.create(tasks).criticalPathNanos() == 23L,
                "critical path did not select root -> long -> join");
        StartupTask saturatedRoot = configuredTask("critical:saturated-root", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of(), Long.MAX_VALUE - 5L, () -> {
                });
        StartupTask saturatedDependent = configuredTask("critical:saturated-dependent", StartupTask.Kind.CPU_PURE,
                Set.of(), Set.of(), Set.of("critical:saturated-root"), 10L, () -> {
                });
        require(StartupTaskPlan.create(List.of(saturatedRoot, saturatedDependent)).criticalPathNanos()
                        == Long.MAX_VALUE,
                "overflowing critical path estimate did not saturate");
        require(StartupTaskPlan.create(List.of()).criticalPathNanos() == 0L,
                "empty plan critical path was not zero");
    }

    private static StartupTask task(String id, StartupTask.Kind kind, Runnable action) {
        return StartupTask.builder(id, kind, action).build();
    }

    private static StartupTask configuredTask(String id, StartupTask.Kind kind,
                                               Set<String> reads, Set<String> writes,
                                               Set<String> after, long estimatedNanos,
                                               Runnable action) {
        return StartupTask.builder(id, kind, action)
                .reads(reads)
                .writes(writes)
                .after(after)
                .estimatedNanos(estimatedNanos)
                .build();
    }

    private static StartupTaskPlan.Executors allKinds(Executor executor) {
        return new StartupTaskPlan.Executors(executor, executor, executor, executor,
                executor, executor, executor);
    }

    private static StartupTaskPlan.Executors executorsFrom(Map<StartupTask.Kind, ? extends Executor> executors) {
        return new StartupTaskPlan.Executors(
                executors.get(StartupTask.Kind.CPU_PURE),
                executors.get(StartupTask.Kind.BLOCKING_IO),
                executors.get(StartupTask.Kind.CLASS_DEFINE),
                executors.get(StartupTask.Kind.MAIN_THREAD),
                executors.get(StartupTask.Kind.RENDER_THREAD),
                executors.get(StartupTask.Kind.LOW_PRIORITY_WRITEBACK),
                executors.get(StartupTask.Kind.LEGACY));
    }

    private static void await(CompletableFuture<Void> future) throws Exception {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            require(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "timed out waiting for " + description);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + description, exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        require(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "executor did not terminate");
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, String messageFragment,
                                                        ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            require(type.isInstance(throwable),
                    "expected " + type.getSimpleName() + " but got " + throwable);
            if (messageFragment != null) {
                require(throwable.getMessage() != null && throwable.getMessage().contains(messageFragment),
                        "exception message did not contain '" + messageFragment + "': " + throwable.getMessage());
            }
            return type.cast(throwable);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.addLast(command);
        }

        private int size() {
            return queued.size();
        }

        private void runExactly(int count) {
            for (int index = 0; index < count; index++) {
                Runnable command = queued.pollFirst();
                require(command != null, "manual executor had fewer than " + count + " queued tasks");
                command.run();
            }
        }

        private void runAll() {
            while (!queued.isEmpty()) {
                queued.removeFirst().run();
            }
        }
    }
}

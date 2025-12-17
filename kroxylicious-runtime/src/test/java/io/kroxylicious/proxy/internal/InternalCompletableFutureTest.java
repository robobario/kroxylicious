/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

class InternalCompletableFutureTest {

    private static EventLoop executor;
    private final static AtomicReference<Thread> actualThread = new AtomicReference<>();

    @BeforeAll
    static void beforeAll() {
        executor = new DefaultEventLoop(Executors.newSingleThreadExecutor());
    }

    @AfterAll
    static void afterAll() {
        executor.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }

    @BeforeEach
    void setUp() {
        actualThread.set(null);
    }

    @Test
    void asyncChainingMethodExecutesOnThreadOfExecutor() throws Exception {
        var threadOfExecutor = executor.submit(Thread::currentThread).get();
        var future = InternalCompletableFuture.completedFuture(executor, null);

        assertThat(future.thenAcceptAsync(InternalCompletableFutureTest::captureThread))
                .succeedsWithin(Duration.ofMillis(100));
        assertThat(actualThread).hasValue(threadOfExecutor);
    }

    @Test
    void minimalStageComposesAsParameter() {
        CompletionStage<Object> stage = InternalCompletableFuture.completedFuture(executor, null).minimalCompletionStage();
        CompletableFuture<Object> thenCompose = CompletableFuture.completedFuture(null).thenCompose(f -> stage);
        assertThat(thenCompose).succeedsWithin(Duration.ZERO);
    }

    @Test
    void asyncChainingMethodExecutesOnThreadOfExecutorEither() throws Exception {
        var threadOfExecutor = executor.submit(Thread::currentThread).get();
        var future = new InternalCompletableFuture<>(executor);

        assertThat(future.acceptEitherAsync(CompletableFuture.completedFuture(null),
                (u) -> {
                }).thenAcceptAsync(InternalCompletableFutureTest::captureThread))
                .succeedsWithin(Duration.ofMillis(100));

        assertThat(actualThread).hasValue(threadOfExecutor);
    }

    static Stream<Arguments> allExceptionalMethods() {
        return Stream.of(
                argumentSet("exceptionally", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.exceptionally(
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("exceptionallyAsync", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.exceptionallyAsync(
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("exceptionallyAsync(E)", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.exceptionallyAsync(
                        InternalCompletableFutureTest::captureThreadWithResult, e)),

                argumentSet("exceptionallyCompose",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.exceptionallyCompose(
                                InternalCompletableFutureTest::captureThreadChainedResult)),
                argumentSet("exceptionallyComposeAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.exceptionallyComposeAsync(
                                InternalCompletableFutureTest::captureThreadChainedResult)),
                argumentSet("exceptionallyComposeAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.exceptionallyComposeAsync(
                                InternalCompletableFutureTest::captureThreadChainedResult, e)));
    }

    static Stream<Arguments> allChainingMethods() {
        var other = CompletableFuture.<Void> completedFuture(null);
        return Stream.of(
                argumentSet("thenAccept",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenAccept(InternalCompletableFutureTest::captureThread)),
                argumentSet("thenAcceptAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenAcceptAsync(InternalCompletableFutureTest::captureThread)),
                argumentSet("thenAcceptAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenAcceptAsync(InternalCompletableFutureTest::captureThread,
                                e)),

                argumentSet("thenApply", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenApply(
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("thenApplyAsync", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenApplyAsync(
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("thenApplyAsync(E)", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenApplyAsync(
                        InternalCompletableFutureTest::captureThreadWithResult, e)),

                argumentSet("thenCombine", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenCombine(other,
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("thenCombineAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenCombineAsync(other,
                                InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("thenCombineAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenCombineAsync(other,
                                InternalCompletableFutureTest::captureThreadWithResult, e)),

                argumentSet("thenCompose", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenCompose(
                        InternalCompletableFutureTest::captureThreadChainedResult)),
                argumentSet("thenComposeAsync", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenComposeAsync(
                        InternalCompletableFutureTest::captureThreadChainedResult)),
                argumentSet("thenComposeAsync(E)", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenComposeAsync(
                        InternalCompletableFutureTest::captureThreadChainedResult, e)),

                argumentSet("thenRun",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenRun(InternalCompletableFutureTest::captureThread)),
                argumentSet("thenRunAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenRunAsync(InternalCompletableFutureTest::captureThread)),
                argumentSet("thenRunAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenRunAsync(InternalCompletableFutureTest::captureThread, e)),

                argumentSet("handle",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.handle(InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("handleAsync", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.handleAsync(
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("handleAsync(E)", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.handleAsync(
                        InternalCompletableFutureTest::captureThreadWithResult, e)),

                argumentSet("whenComplete",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.whenComplete(InternalCompletableFutureTest::captureThread)),
                argumentSet("whenCompleteAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.whenCompleteAsync(InternalCompletableFutureTest::captureThread)),
                argumentSet("whenCompleteAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.whenCompleteAsync(InternalCompletableFutureTest::captureThread,
                                e)),
                argumentSet("acceptEither", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.acceptEither(other,
                        InternalCompletableFutureTest::captureThread)),
                argumentSet("acceptEitherAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.acceptEitherAsync(other,
                                InternalCompletableFutureTest::captureThread)),
                argumentSet("acceptEitherAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.acceptEitherAsync(other,
                                InternalCompletableFutureTest::captureThread, e)),

                argumentSet("applyToEither", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.applyToEither(other,
                        InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("applyToEitherAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.applyToEitherAsync(other,
                                InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("applyToEitherAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.applyToEitherAsync(other,
                                InternalCompletableFutureTest::captureThreadWithResult, e)),

                argumentSet("runAfterEither", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.runAfterEither(other,
                        InternalCompletableFutureTest::captureThread)),
                argumentSet("runAfterEitherAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.runAfterEitherAsync(other,
                                InternalCompletableFutureTest::captureThread)),
                argumentSet("runAfterEitherAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.runAfterEitherAsync(other,
                                InternalCompletableFutureTest::captureThread, e)),

                argumentSet("theAcceptBoth",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenAcceptBoth(other,
                                InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("thenAcceptBothAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenAcceptBothAsync(other,
                                InternalCompletableFutureTest::captureThreadWithResult)),
                argumentSet("thenAcceptBothAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.thenAcceptBothAsync(other,
                                InternalCompletableFutureTest::captureThreadWithResult, e)),

                argumentSet("runAfterBoth", (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.runAfterBoth(other,
                        InternalCompletableFutureTest::captureThread)),
                argumentSet("runAfterBothAsync",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.runAfterBothAsync(other,
                                InternalCompletableFutureTest::captureThread)),
                argumentSet("runAfterBothAsync(E)",
                        (BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>>) (s, e) -> s.runAfterBothAsync(other,
                                InternalCompletableFutureTest::captureThread, e)));
    }

    @ParameterizedTest()
    @MethodSource("allChainingMethods")
    void chainedWorkIsExecutedOnEventLoop(BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>> func) throws ExecutionException, InterruptedException {
        var threadOfExecutor = executor.submit(Thread::currentThread).get();
        var future = new InternalCompletableFuture<Void>(executor);
        var stage = future.minimalCompletionStage();
        var result = func.apply(stage, executor);
        assertThat(result.getClass()).isAssignableTo(InternalCompletionStage.class);
        CompletableFuture<Void> future1 = result.toCompletableFuture();
        future.complete(null);
        assertThat(future1).succeedsWithin(2, TimeUnit.SECONDS);
        assertThat(actualThread).hasValue(threadOfExecutor);
    }

    @ParameterizedTest()
    @MethodSource("allChainingMethods")
    void chainedWorkIsExecutedOnEventLoopFuture(BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>> func) throws ExecutionException, InterruptedException {
        var threadOfExecutor = executor.submit(Thread::currentThread).get();
        var future = new InternalCompletableFuture<Void>(executor);
        var result = func.apply(future, executor);
        assertThat(result.getClass()).isAssignableTo(InternalCompletableFuture.class);
        CompletableFuture<Void> future1 = result.toCompletableFuture();
        future.complete(null);
        assertThat(future1).succeedsWithin(2, TimeUnit.SECONDS);
        assertThat(future1).isInstanceOf(InternalCompletableFuture.class);
        // actualThread should populated by one of the `captureThread` family of methods.
        assertThat(actualThread).hasValue(threadOfExecutor);
    }

    @ParameterizedTest()
    @MethodSource("allExceptionalMethods")
    void exceptionHandlingWorkIsExecutedOnEventLoop(BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>> func)
            throws ExecutionException, InterruptedException {
        // Given
        var threadOfExecutor = executor.submit(Thread::currentThread).get();
        var future = new InternalCompletableFuture<Void>(executor);
        var stage = future.minimalCompletionStage();
        var result = func.apply(stage, executor);
        assertThat(result.getClass()).isAssignableTo(InternalCompletionStage.class);
        CompletableFuture<Void> future1 = result.toCompletableFuture();

        // When
        future.completeExceptionally(new IllegalStateException("Whoops it went wrong"));

        // Then
        assertThat(future1).succeedsWithin(2, TimeUnit.SECONDS);
        // actualThread should populated by one of the `captureThread` family of methods.
        assertThat(actualThread).hasValue(threadOfExecutor);
    }

    @ParameterizedTest()
    @MethodSource("allExceptionalMethods")
    void exceptionHandlingWorkIsExecutedOnEventLoopFuture(BiFunction<CompletionStage<Void>, Executor, CompletionStage<Void>> func)
            throws ExecutionException, InterruptedException {
        // Given
        var threadOfExecutor = executor.submit(Thread::currentThread).get();
        var future = new InternalCompletableFuture<Void>(executor);
        var result = func.apply(future, executor);
        assertThat(result.getClass()).isAssignableTo(InternalCompletableFuture.class);
        CompletableFuture<Void> future1 = result.toCompletableFuture();

        // When
        future.completeExceptionally(new IllegalStateException("Whoops it went wrong"));

        // Then
        assertThat(future1).succeedsWithin(2, TimeUnit.SECONDS);

        // actualThread should populated by one of the `captureThread` family of methods.
        assertThat(actualThread).hasValue(threadOfExecutor);
    }

    private static void captureThread() {
        assertThread();
    }

    private static <T> void captureThread(T ignored) {
        assertThread();
    }

    private static <T> void captureThread(T ignored, Throwable ignoredThrowable) {
        assertThread();
    }

    private static <T> T captureThreadWithResult(T ignored) {
        assertThread();
        return ignored;
    }

    private static <T> T captureThreadWithResult(Throwable ignored) {
        assertThread();
        return null;
    }

    private static <T, U> T captureThreadWithResult(T ignored, U after) {
        assertThread();
        return ignored;
    }

    private static <T> CompletableFuture<Void> captureThreadChainedResult(T ignored) {
        assertThread();
        return CompletableFuture.completedFuture(null);
    }

    private static void assertThread() {
        assertThat(executor.inEventLoop()).isTrue();
        assertThat(actualThread.compareAndSet(null, Thread.currentThread())).isTrue();
    }

}

/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.channel.EventLoop;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implementation of CompletableFuture that executes all chained work on a specific
 * event loop.
 * <br/>
 * @param <T> The result type returned by this future's {@code join}
 * and {@code get} methods.
 * @see InternalCompletionStage
 */
class InternalCompletableFuture<T> extends CompletableFuture<T> {

    private final EventLoop eventLoop;

    InternalCompletableFuture(EventLoop eventLoop) {
        this.eventLoop = Objects.requireNonNull(eventLoop);
    }

    /**
     * Returns a new incomplete InternalCompletableFuture of the type to be
     * returned by a CompletionStage method.
     *
     * @param <U> the type of the value
     * @return a new CompletableFuture
     */
    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new InternalCompletableFuture<>(eventLoop);
    }

    /**
     * Returns the default Executor used for async methods that do not specify an Executor.
     *
     * @return the default executor
     */
    @Override
    public Executor defaultExecutor() {
        return eventLoop;
    }

    /**
     * Returns a new CompletionStage that is completed normally with
     * the same value as this CompletableFuture when it completes
     * normally, and cannot be independently completed or otherwise
     * used in ways not defined by the methods of interface {@link
     * CompletionStage}.  If this CompletableFuture completes
     * exceptionally, then the returned CompletionStage completes
     * exceptionally with a CompletionException with this exception as
     * cause.
     * <br/>
     */
    @Override
    public CompletionStage<T> minimalCompletionStage() {
        return new InternalCompletionStage<>(this);
    }

    /**
     * Returns a new CompletableFuture that is already completed with the given value.
     * @param value – the value
     * @param <U> the type of the value
     * @return the completed CompletableFuture
     */
    public static <U> CompletableFuture<U> completedFuture(EventLoop executor, @Nullable U value) {
        var f = new InternalCompletableFuture<U>(executor);
        f.complete(value);
        return f;
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return super.thenCompose(t -> {
            if (isInEventLoop()) {
                return super.thenApply(fn);
            }
            else {
                return super.thenApplyAsync(fn);
            }
        });
    }

    private boolean isInEventLoop() {
        return eventLoop.inEventLoop();
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return super.thenCompose(t -> {
            if (isInEventLoop()) {
                return super.thenAccept(action);
            }
            else {
                return super.thenAcceptAsync(action);
            }
        });
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        return super.thenCompose(t -> {
            if (isInEventLoop()) {
                return super.thenRun(action);
            }
            else {
                return super.thenRunAsync(action);
            }
        });
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        CompletionStage<? extends U> internalFuture = toInternalFuture(other);
        return super.thenCombineAsync(internalFuture, fn);
    }

    private <U> CompletionStage<? extends U> toInternalFuture(CompletionStage<? extends U> other) {
        if (other instanceof InternalCompletableFuture) {
            return other;
        }
        CompletableFuture<U> incompleteFuture = newIncompleteFuture();
        other.whenComplete((u, throwable) -> {
            if (eventLoop.inEventLoop()) {
                if (throwable != null) {
                    incompleteFuture.completeExceptionally(throwable);
                }
                else {
                    incompleteFuture.complete(u);
                }
            }
            else {
                eventLoop.execute(() -> {
                    if (throwable != null) {
                        incompleteFuture.completeExceptionally(throwable);
                    }
                    else {
                        incompleteFuture.complete(u);
                    }
                });
            }
        });
        return incompleteFuture;
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        CompletionStage<? extends U> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.thenAcceptBothAsync(internalFuture, action);
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        CompletionStage<?> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.runAfterBothAsync(internalFuture, action);
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        CompletionStage<? extends T> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.applyToEitherAsync(internalFuture, fn);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        CompletionStage<? extends T> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.applyToEitherAsync(internalFuture, fn);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
        CompletionStage<? extends T> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.applyToEitherAsync(internalFuture, fn, executor);
    }

    @Override
    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        CompletionStage<? extends T> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.acceptEitherAsync(internalFuture, action);
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        CompletionStage<? extends T> internalFuture = toInternalFuture(other);
        return super.acceptEitherAsync(internalFuture, action);
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        CompletionStage<? extends T> internalFuture = toInternalFuture(other);
        return super.acceptEitherAsync(internalFuture, action, executor);
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        CompletionStage<?> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.runAfterEitherAsync(internalFuture, action);
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        CompletionStage<?> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.runAfterEitherAsync(internalFuture, action);
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        CompletionStage<?> internalFuture = toInternalFuture(other);
        // go async to ensure function executed on event loop
        return super.runAfterEitherAsync(internalFuture, action, executor);
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return super.thenCompose(t -> {
            if (isInEventLoop()) {
                return super.thenCompose(fn);
            }
            else {
                return super.thenComposeAsync(fn);
            }
        });
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        InternalCompletableFuture<T> tCompletableFuture = new InternalCompletableFuture<>(eventLoop);
        CompletableFuture<U> incompleteFuture = tCompletableFuture.underlyingHandle(fn);
        dispatchOnEventLoop(tCompletableFuture);
        return incompleteFuture;
    }

    private <U> CompletableFuture<U> underlyingHandle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return super.handle(fn);
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        InternalCompletableFuture<T> tCompletableFuture = new InternalCompletableFuture<>(eventLoop);
        CompletableFuture<T> incompleteFuture = tCompletableFuture.underlyingWhenComplete(action);
        dispatchOnEventLoop(tCompletableFuture);
        return incompleteFuture;
    }

    private CompletableFuture<T> underlyingWhenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return super.whenComplete(action);
    }

    private void dispatchOnEventLoop(CompletableFuture<T> incompleteFuture) {
        super.whenComplete((t, throwable) -> {
            if (isInEventLoop()) {
                if (throwable != null) {
                    incompleteFuture.completeExceptionally(throwable);
                }
                else {
                    incompleteFuture.complete(t);
                }
            }
            else {
                eventLoop.execute(() -> {
                    if (throwable != null) {
                        incompleteFuture.completeExceptionally(throwable);
                    }
                    else {
                        incompleteFuture.complete(t);
                    }
                });
            }
        });
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return super.exceptionallyCompose(t -> {
            if (isInEventLoop()) {
                return super.exceptionally(fn);
            }
            else {
                return super.exceptionallyAsync(fn);
            }
        });
    }

    @Override
    public CompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return super.exceptionallyCompose(t -> {
            if (isInEventLoop()) {
                return super.exceptionallyCompose(fn);
            }
            else {
                return super.exceptionallyComposeAsync(fn);
            }
        });
    }

}

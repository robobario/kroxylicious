/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.kroxylicious.proxy.filter.FilterAndInvoker;
import io.kroxylicious.proxy.filter.FilterInvoker;
import io.kroxylicious.proxy.filter.FilterResult;
import io.kroxylicious.proxy.filter.KrpcFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;
import io.kroxylicious.proxy.frame.OpaqueRequestFrame;
import io.kroxylicious.proxy.frame.OpaqueResponseFrame;
import io.kroxylicious.proxy.internal.util.Assertions;

/**
 * A {@code ChannelInboundHandler} (for handling requests from downstream)
 * that applies a single {@link KrpcFilter}.
 */
public class FilterHandler extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterHandler.class);
    private final KrpcFilter filter;
    private final long timeoutMs;
    private final String sniHostname;
    private final Channel inboundChannel;
    private final FilterInvoker invoker;
    private CompletableFuture<Void> writeFuture = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> readFuture = CompletableFuture.completedFuture(null);

    public FilterHandler(FilterAndInvoker filterAndInvoker, long timeoutMs, String sniHostname, Channel inboundChannel) {
        this.filter = Objects.requireNonNull(filterAndInvoker).filter();
        this.invoker = filterAndInvoker.invoker();
        this.timeoutMs = Assertions.requireStrictlyPositive(timeoutMs, "timeout");
        this.sniHostname = sniHostname;
        this.inboundChannel = inboundChannel;
    }

    String filterDescriptor() {
        return filter.getClass().getSimpleName() + "@" + System.identityHashCode(filter);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof InternalRequestFrame<?>) {
            // jump the queue, internal request must flow!
            doWrite(ctx, msg, promise);
        }
        else if (writeFuture.isDone()) {
            writeFuture = doWrite(ctx, msg, promise);
        }
        else {
            writeFuture = writeFuture.whenComplete((a, b) -> doWrite(ctx, msg, promise));
        }
    }

    private CompletableFuture<Void> doWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof DecodedRequestFrame<?> decodedFrame) {
            var filterContext = new DefaultFilterContext(filter, ctx, decodedFrame, promise, timeoutMs, sniHostname);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{}: Dispatching downstream {} request to filter{}: {}",
                        ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
            }

            var stage = invoker.onRequest(decodedFrame.apiKey(), decodedFrame.apiVersion(), decodedFrame.header(),
                    decodedFrame.body(), filterContext).toCompletableFuture();
            var withTimeout = handleDeferredStage(ctx, stage);
            var onNettyThread = switchBackToNettyIfRequired(ctx, withTimeout);
            return onNettyThread.whenComplete((filterResult, t) -> {

                if (t != null) {
                    filterContext.closeConnection();
                    return;
                }

                if (filterResult instanceof RequestFilterResult rfr) {
                    var header = rfr.header() == null ? decodedFrame.header() : rfr.header();
                    filterContext.forwardRequest(header, rfr.message());
                }
                else if (filterResult instanceof ResponseFilterResult rfr) {
                    // this is the short circuit path
                    if (rfr.message() != null) {
                        var header = rfr.header() == null ? new ResponseHeaderData().setCorrelationId(decodedFrame.correlationId()) : rfr.header();
                        filterContext.forwardResponse(header, rfr.message());
                    }
                }
                if (filterResult.closeConnection()) {
                    filterContext.closeConnection();
                }
            }).toCompletableFuture().thenApply(filterResult -> null);

        }
        else {
            if (!(msg instanceof OpaqueRequestFrame)
                    && msg != Unpooled.EMPTY_BUFFER) {
                // Unpooled.EMPTY_BUFFER is used by KafkaProxyFrontendHandler#closeOnFlush
                // but otherwise we don't expect any other kind of message
                LOGGER.warn("Unexpected message writing to upstream: {}", msg, new IllegalStateException());
            }
            ctx.write(msg, promise);
            return CompletableFuture.completedFuture(null);
        }
    }

    private <T> CompletableFuture<T> switchBackToNettyIfRequired(ChannelHandlerContext ctx, CompletableFuture<T> future) {
        if (ctx.executor().inEventLoop()) {
            return future;
        }
        CompletableFuture<T> nettyDrivenFuture = new CompletableFuture<>();
        future.whenComplete((t, throwable) -> {
            ctx.executor().execute(() -> {
                if (throwable != null) {
                    nettyDrivenFuture.completeExceptionally(throwable);
                }
                else {
                    nettyDrivenFuture.complete(t);
                }
            });
        });
        return nettyDrivenFuture;
    }

    private <T extends FilterResult> CompletableFuture<T> handleDeferredStage(ChannelHandlerContext ctx, CompletableFuture<T> stage) {
        if (!stage.isDone()) {
            inboundChannel.config().setAutoRead(false);
            ctx.executor().schedule(() -> {
                stage.completeExceptionally(new TimeoutException());
            }, timeoutMs, TimeUnit.MILLISECONDS);
            return stage.thenApply(filterResult -> {
                inboundChannel.config().setAutoRead(true);
                return filterResult;
            });
        }
        else {
            return stage;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof InternalResponseFrame<?> decodedFrame) {
            // jump the queue, let extra requests flow back to their sender
            if (decodedFrame.isRecipient(filter)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{}: Completing {} response for request sent by this filter{}: {}",
                            ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
                }
                CompletableFuture<ApiMessage> p = decodedFrame.promise();
                p.complete(decodedFrame.body());
            }
            else {
                doRead(ctx, msg);
            }
        }
        else if (readFuture.isDone()) {
            readFuture = doRead(ctx, msg);
        }
        else {
            readFuture = readFuture.whenComplete((a, b) -> doRead(ctx, msg));
        }
    }

    private CompletableFuture<Void> doRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof DecodedResponseFrame<?> decodedFrame) {
            var filterContext = new DefaultFilterContext(filter, ctx, decodedFrame, null, timeoutMs, sniHostname);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{}: Dispatching upstream {} response to filter {}: {}",
                        ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
            }
            var stage = invoker.onResponse(decodedFrame.apiKey(), decodedFrame.apiVersion(),
                    decodedFrame.header(), decodedFrame.body(), filterContext).toCompletableFuture();
            var withTimeout = handleDeferredStage(ctx, stage);
            var onNettyThread = switchBackToNettyIfRequired(ctx, withTimeout);
            return onNettyThread.whenComplete((rfr, t) -> {
                if (t != null) {
                    filterContext.closeConnection();
                    return;
                }
                if (rfr.message() != null) {
                    ResponseHeaderData header = rfr.header() == null ? decodedFrame.header() : rfr.header();
                    filterContext.forwardResponse(header, rfr.message());
                }
                if (rfr.closeConnection()) {
                    filterContext.closeConnection();
                }

            }).thenApply(responseFilterResult -> null);
        }
        else {
            if (!(msg instanceof OpaqueResponseFrame)) {
                LOGGER.warn("Unexpected message reading from upstream: {}", msg, new IllegalStateException());
            }
            ctx.fireChannelRead(msg);
            return CompletableFuture.completedFuture(null);
        }
    }

}

/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.kroxylicious.proxy.filter.KrpcFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.RequestFilterResultBuilder;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResultBuilder;
import io.kroxylicious.proxy.frame.DecodedFrame;
import io.kroxylicious.proxy.future.InternalCompletionStage;
import io.kroxylicious.proxy.internal.filter.RequestFilterResultBuilderImpl;
import io.kroxylicious.proxy.internal.filter.ResponseFilterResultBuilderImpl;
import io.kroxylicious.proxy.internal.util.ByteBufOutputStream;
import io.kroxylicious.proxy.model.VirtualCluster;

/**
 * Implementation of {@link KrpcFilterContext}.
 */
class DefaultFilterContext implements KrpcFilterContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultFilterContext.class);

    private final DecodedFrame<?, ?> decodedFrame;
    private final ChannelHandlerContext channelContext;
    private final KrpcFilter filter;
    private final long timeoutMs;
    private final String sniHostname;
    private final VirtualCluster virtualCluster;

    DefaultFilterContext(KrpcFilter filter,
                         ChannelHandlerContext channelContext,
                         DecodedFrame<?, ?> decodedFrame,
                         long timeoutMs,
                         String sniHostname,
                         VirtualCluster virtualCluster) {
        this.filter = filter;
        this.channelContext = channelContext;
        this.decodedFrame = decodedFrame;
        this.timeoutMs = timeoutMs;
        this.sniHostname = sniHostname;
        this.virtualCluster = virtualCluster;
    }

    /**
     * Get a description of the channel, typically used for logging.
     * @return a description of the channel.
     */
    @Override
    public String channelDescriptor() {
        return channelContext.channel().toString();
    }

    /**
     * Create a ByteBufferOutputStream of the given capacity.
     * The backing buffer will be deallocated when the request processing is completed
     * @param initialCapacity The initial capacity of the buffer.
     * @return The allocated ByteBufferOutputStream
     */
    @Override
    public ByteBufferOutputStream createByteBufferOutputStream(int initialCapacity) {
        final ByteBuf buffer = channelContext.alloc().ioBuffer(initialCapacity);
        decodedFrame.add(buffer);
        return new ByteBufOutputStream(buffer);
    }

    @Override
    public String sniHostname() {
        return sniHostname;
    }

    @Override
    public <T extends ApiMessage> CompletionStage<T> sendRequest(short apiVersion, ApiMessage message) {
        short key = message.apiKey();
        var apiKey = ApiKeys.forId(key);
        short headerVersion = apiKey.requestHeaderVersion(apiVersion);
        var header = new RequestHeaderData()
                .setCorrelationId(-1)
                .setRequestApiKey(key)
                .setRequestApiVersion(apiVersion);
        if (headerVersion > 1) {
            header.setClientId(filter.getClass().getSimpleName() + "@" + System.identityHashCode(filter));
        }
        boolean hasResponse = apiKey != ApiKeys.PRODUCE
                || ((ProduceRequestData) message).acks() != 0;
        var filterPromise = new CompletableFuture<T>();
        var filterStage = new InternalCompletionStage<>(filterPromise);
        var frame = new InternalRequestFrame<>(
                apiVersion, -1, hasResponse,
                filter, filterPromise, header, message);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{}: Sending request: {}", channelDescriptor(), frame);
        }
        ChannelPromise writePromise = channelContext.channel().newPromise();
        // if (outboundCtx.channel().isWritable()) {
        // outboundCtx.write(frame, writePromise);
        // }
        // else {
        channelContext.writeAndFlush(frame, writePromise);
        // }

        if (!hasResponse) {
            // Complete the filter promise for an ack-less Produce
            // based on the success of the channel write
            // (for all other requests the filter promise will be completed
            // when handling the response).
            writePromise.addListener(f -> {
                if (f.isSuccess()) {
                    filterPromise.complete(null);
                }
                else {
                    filterPromise.completeExceptionally(f.cause());
                }
            });
        }

        channelContext.executor().schedule(() -> {
            LOGGER.debug("{}: Timing out {} request after {}ms", channelContext, apiKey, timeoutMs);
            filterPromise.completeExceptionally(new TimeoutException());
        }, timeoutMs, TimeUnit.MILLISECONDS);
        return filterStage;
    }

    @Override
    public CompletionStage<ResponseFilterResult> forwardResponse(ResponseHeaderData header, ApiMessage response) {
        return responseFilterResultBuilder().forward(header, response).completed();
    }

    @Override
    public ResponseFilterResultBuilder responseFilterResultBuilder() {
        return new ResponseFilterResultBuilderImpl();
    }

    @Override
    public CompletionStage<RequestFilterResult> forwardRequest(RequestHeaderData header, ApiMessage request) {
        return requestFilterResultBuilder().forward(header, request).completed();
    }

    @Override
    public RequestFilterResultBuilder requestFilterResultBuilder() {
        return new RequestFilterResultBuilderImpl();
    }

    public String getVirtualClusterName() {
        return virtualCluster.getClusterName();
    }

}

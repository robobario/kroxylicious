/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.protocol.ApiKeys;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;

/**
 * <p>Interface for {@code *RequestFilter}s.
 * This interface is not usually implemented directly.
 * Instead filter classes can (multiply) implement one of the RPC-specific subinterfaces such
 * as {@link ProduceRequestFilter} for a type-safe API.
 *
 * <p>When implementing one or more of the {@code *RequestFilter} subinterfaces you need only implement
 * the {@code on*Request} method(s), unless your filter can avoid deserialization in which case
 * you can override {@link #shouldDeserializeRequest(ApiKeys, short)} as well.</p>
 *
 * <h3>Guarantees</h3>
 * <p>Implementors of this API may assume the following:</p>
 * <ol>
 *     <li>That each instance of the filter is associated with a single channel</li>
 *     <li>That {@link #shouldDeserializeRequest(ApiKeys, short)} and
 *     {@link #apply(DecodedRequestFrame, KrpcFilterContext)} (or {@code on*Request} as appropriate)
 *     will always be invoked on the same thread.</li>
 *     <li>That filters are applied in the order they were configured.</li>
 * </ol>
 * <p>From 1. and 2. it follows that you can use member variables in your filter to
 * store channel-local state.</p>
 *
 * <p>Implementors should <strong>not</strong> assume:</p>
 * <ol>
 *     <li>That filters in the same chain execute on the same thread. Thus inter-filter communication/state
 *     transfer needs to be thread-safe</li>
 * </ol>
 */
public /* sealed */ interface KrpcFilter extends RequestTargeter, ResponseTargeter /* TODO permits ... */ {

    default void onRequest(DecodedRequestFrame<?> decodedFrame, KrpcFilterContext filterContext) {
        filterContext.forwardRequest(decodedFrame.body());
    }

    default void onResponse(DecodedResponseFrame<?> decodedFrame, KrpcFilterContext filterContext) {
        filterContext.forwardResponse(decodedFrame.body());
    }

    static KrpcFilter of(Object o) {
        boolean implementsAnyMessageInterface = TurboInvoker.implementsAnyMessageInterface(o);
        boolean isKrpcFilter = o instanceof KrpcFilter;
        if (isKrpcFilter && implementsAnyMessageInterface) {
            throw new IllegalArgumentException("filter object cannot implement both KrpcFilter and specific interfaces");
        }
        else if (isKrpcFilter) {
            // user directly implemented KrpcFilter
            return (KrpcFilter) o;
        }
        else if (implementsAnyMessageInterface) {
            // user implemented other specific message filter interfaces
            return new TurboInvoker(o);
        }
        else {
            throw new IllegalArgumentException("object was not a KrpcFilter and did not implement any specific message interfaces");
        }
    }

}

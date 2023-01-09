/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.protocol.ApiKeys;

public interface RequestTargeter {

    /**
     * <p>Determines whether a request with the given {@code apiKey} and {@code apiVersion} should be deserialized.
     * Note that it is not guaranteed that this method will be called once per request,
     * or that two consecutive calls refer to the same request.
     * That is, the sequences of invocations like the following are allowed:</p>
     * <ol>
     *     <li>{@code shouldDeserializeRequest} on request A</li>
     *     <li>{@code shouldDeserializeRequest} on request B</li>
     *     <li>{@code shouldDeserializeRequest} on request A</li>
     *     <li>{@code apply} on request A</li>
     *     <li>{@code apply} on request B</li>
     * </ol>
     * @param apiKey The API key
     * @param apiVersion The API version
     * @return
     */
    boolean shouldDeserializeRequest(ApiKeys apiKey, short apiVersion);

}

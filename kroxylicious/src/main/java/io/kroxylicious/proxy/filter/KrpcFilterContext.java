/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.protocol.ApiMessage;

import io.netty.buffer.ByteBuf;

/**
 * A context to allow filters to interact with other filters and the pipeline.
 */
public interface KrpcFilterContext {
    /**
     * @return A description of this channel (typically used for logging).
     */
    String channelDescriptor();

    /**
     * Allocate a ByteBuffer of the given capacity.
     * The buffer will be deallocated when the request processing is completed
     * @param initialCapacity The initial capacity of the buffer.
     * @return The allocated buffer
     */
    ByteBuf allocate(int initialCapacity);

    /**
     * Send a request towards the broker, invoking upstream filters.
     * @param request The request to forward to the broker.
     */
    void forwardRequest(ApiMessage request);

    /**
     * Send a response towards the client, invoking downstream filters.
     * @param response The response to forward to the client.
     */
    void forwardResponse(ApiMessage response);

    // TODO an API to allow a filter to add/remove another filter from the pipeline
}

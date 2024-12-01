/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.codec;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.apache.kafka.common.protocol.Writable;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;

import static io.kroxylicious.proxy.internal.codec.DowngradeRequestHeaderData.apiVersionsRequestDowngradeHeader;

/**
 * This subclass is used when we receive an ApiVersions request at an api version higher
 * than the proxy supports. It should be handled internally with a short-circuit response
 * and not forwarded to any broker.
 */
public class DowngradeApiVersionsRequestData extends ApiVersionsRequestData {

    private DowngradeApiVersionsRequestData() {
        super();
    }

    @Override
    public void write(Writable writable, ObjectSerializationCache cache, short version) {
        throw new UnsupportedOperationException("DowngradeApiVersionsRequestData is read-only");
    }

    @Override
    public int size(ObjectSerializationCache cache, short version) {
        throw new UnsupportedOperationException("DowngradeApiVersionsRequestData is read-only");
    }

    public static DecodedRequestFrame<ApiVersionsRequestData> downgradeApiVersionsFrame(int correlationId) {
        RequestHeaderData requestHeaderData = apiVersionsRequestDowngradeHeader(correlationId);
        return new DecodedRequestFrame<>(
                requestHeaderData.requestApiVersion(), correlationId, true, requestHeaderData, new DowngradeApiVersionsRequestData());
    }
}

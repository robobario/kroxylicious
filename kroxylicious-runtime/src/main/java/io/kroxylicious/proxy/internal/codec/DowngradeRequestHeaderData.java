/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.codec;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.apache.kafka.common.protocol.Writable;

/**
 * This subclass is used when we receive an ApiVersions request at an api version higher
 * than the proxy supports. It should be handled internally with a short-circuit response
 * and not forwarded further to any broker.
 */
public class DowngradeRequestHeaderData extends RequestHeaderData {

    private DowngradeRequestHeaderData() {
        super();
    }

    @Override
    public void write(Writable writable, ObjectSerializationCache cache, short version) {
        throw new UnsupportedOperationException("DowngradeRequestHeaderData is read-only");
    }

    @Override
    public int size(ObjectSerializationCache cache, short version) {
        throw new UnsupportedOperationException("DowngradeRequestHeaderData is read-only");
    }

    public static RequestHeaderData apiVersionsRequestDowngradeHeader(int correlationId) {
        RequestHeaderData requestHeaderData = new DowngradeRequestHeaderData();
        requestHeaderData.setCorrelationId(correlationId);
        requestHeaderData.setRequestApiKey(ApiKeys.API_VERSIONS.id);
        requestHeaderData.setRequestApiVersion((short) 0);
        return requestHeaderData;
    }
}

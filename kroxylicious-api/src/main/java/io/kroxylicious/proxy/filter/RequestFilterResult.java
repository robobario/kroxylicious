/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.protocol.ApiMessage;

public interface RequestFilterResult<T extends ApiMessage> extends FilterResult<T> {

    ApiMessage shortCircuitResponse();

    static RequestFilterResult<ApiMessage> asApiMessageResult(RequestFilterResult<?> result) {
        // TODO find some less ugly way to convert between specific and general? Could wrap in another implementation
        return (RequestFilterResult<ApiMessage>) result;
    }
}

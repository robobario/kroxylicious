/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;

import io.kroxylicious.proxy.filter.ApiVersionsRequestFilter;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.internal.codec.DowngradeApiVersionsRequestData;

public class ApiVersionsDowngradeFilter implements ApiVersionsRequestFilter {
    @Override
    public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request, FilterContext context) {
        if (request instanceof DowngradeApiVersionsRequestData) {
            ApiVersionsResponseData message = new ApiVersionsResponseData();
            ApiVersionsResponseData.ApiVersionCollection collection = new ApiVersionsResponseData.ApiVersionCollection();
            ApiVersionsResponseData.ApiVersion version = new ApiVersionsResponseData.ApiVersion();
            version.setApiKey(ApiKeys.API_VERSIONS.id);
            version.setMinVersion(ApiKeys.API_VERSIONS.oldestVersion());
            version.setMaxVersion(ApiKeys.API_VERSIONS.latestVersion(true));
            collection.add(version);
            message.setApiKeys(collection);
            message.setErrorCode(Errors.UNSUPPORTED_VERSION.code());
            return context.requestFilterResultBuilder().shortCircuitResponse(message).completed();
        }
        return context.forwardRequest(header, request);
    }
}

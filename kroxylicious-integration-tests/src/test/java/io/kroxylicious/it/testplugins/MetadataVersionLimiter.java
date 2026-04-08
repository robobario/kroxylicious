/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.it.testplugins;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;

import io.kroxylicious.kafka.transform.ApiVersionsResponseTransformer;
import io.kroxylicious.kafka.transform.ApiVersionsResponseTransformers;
import io.kroxylicious.proxy.filter.ApiVersionsResponseFilter;
import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.plugin.Plugin;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;

@Plugin(configType = Void.class)
public class MetadataVersionLimiter implements FilterFactory<Void, MetadataVersionLimiter.ApiVersionLimiterFilter> {

    @Override
    public ApiVersionLimiterFilter initialize(FilterFactoryContext context, Void config) throws PluginConfigurationException {
        return null;
    }

    @Override
    public Filter createFilter(FilterFactoryContext context, ApiVersionLimiterFilter initializationData) {
        return new ApiVersionLimiterFilter();
    }

    public static class ApiVersionLimiterFilter implements ApiVersionsResponseFilter {

        public static final ApiVersionsResponseTransformer TRANSFORM = ApiVersionsResponseTransformers.limitMaxVersionForApiKeys(Map.of(ApiKeys.METADATA, (short) 0));

        @Override
        public CompletionStage<ResponseFilterResult> onApiVersionsResponse(short apiVersion, ResponseHeaderData header, ApiVersionsResponseData response,
                                                                           FilterContext context) {
            TRANSFORM.transform(response);
            return context.forwardResponse(header, response);
        }
    }
}

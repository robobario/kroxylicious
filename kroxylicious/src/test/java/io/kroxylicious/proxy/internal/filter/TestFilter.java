/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.jetbrains.annotations.NotNull;

import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.FilterCreationContext;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.RequestFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;

import edu.umd.cs.findbugs.annotations.NonNull;

public class TestFilter implements RequestFilter {
    private final FilterCreationContext context;
    private final ExampleConfig exampleConfig;
    private final Class<? extends FilterFactory> contributorClass;

    public TestFilter(FilterCreationContext context, ExampleConfig exampleConfig, Class<? extends FilterFactory> contributorClass) {
        this.context = context;
        this.exampleConfig = exampleConfig;
        this.contributorClass = contributorClass;
    }

    @Override
    public CompletionStage<RequestFilterResult> onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage request, FilterContext context) {
        throw new IllegalStateException("not implemented!");
    }

    public FilterCreationContext getContext() {
        return context;
    }

    public ExampleConfig getExampleConfig() {
        return exampleConfig;
    }

    public Class<? extends FilterFactory> getContributorClass() {
        return contributorClass;
    }

    public static class FactoryA implements FilterFactory<TestFilter, ExampleConfig> {

        @NotNull
        @Override
        public Class<TestFilter> filterType() {
            return TestFilter.class;
        }

        @NonNull
        @Override
        public Class<ExampleConfig> configType() {
            return ExampleConfig.class;
        }

        @Override
        public TestFilter createFilter(FilterCreationContext context, ExampleConfig configuration) {
            return new TestFilter(context, configuration, this.getClass());
        }
    }
}
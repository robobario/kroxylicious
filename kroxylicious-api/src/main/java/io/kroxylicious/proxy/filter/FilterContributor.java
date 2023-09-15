/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import io.kroxylicious.proxy.config.BaseConfig;

/**
 * A FilterContributor is a factory for a filter implementation.
 */
public interface FilterContributor<C extends BaseConfig> {

    String getTypeName();

    default Class getFilterType() {
        return Object.class;
    }

    Class<C> getConfigClass();

    /**
     * Creates an instance of the service.
     *
     * @param context   context containing service configuration which may be null if the service instance does not accept configuration.
     * @return the service instance, or null if this contributor does not offer this short name.
     */
    Filter getInstance(C config, FilterConstructContext context);

}

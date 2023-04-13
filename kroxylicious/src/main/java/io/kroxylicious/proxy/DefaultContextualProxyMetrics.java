/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

import io.kroxylicious.proxy.config.VirtualCluster;

public class DefaultContextualProxyMetrics implements ContextualProxyMetrics {
    private final MeterRegistry globalRegistry;
    private final MeterRegistry virtualClusterTagged;

    public DefaultContextualProxyMetrics(String virtualClusterName, CompositeMeterRegistry globalRegistry, VirtualCluster virtualCluster) {
        this.globalRegistry = globalRegistry;
        CompositeMeterRegistry registry = new CompositeMeterRegistry();
        registry.config().commonTags("virtual_cluster_name", virtualClusterName);
        this.virtualClusterTagged = registry.add(globalRegistry);
    }

    @Override
    public MeterRegistry getGlobalRegistry() {
        return globalRegistry;
    }

    @Override
    public MeterRegistry getVirtualClusterTagged() {
        return virtualClusterTagged;
    }
}

/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.it.testplugins.router;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;

import io.kroxylicious.proxy.plugin.Plugin;
import io.kroxylicious.proxy.router.Router;
import io.kroxylicious.proxy.router.RouterContext;
import io.kroxylicious.proxy.router.RouterFactory;
import io.kroxylicious.proxy.router.RouterFactoryContext;
import io.kroxylicious.proxy.router.RouterResponse;
import io.kroxylicious.proxy.topology.VirtualNode;

/**
 * Test router that routes every request to a specific virtual node, selected via
 * {@link RouterContext#nodeForId(int)}. Used to reproduce the contract violation
 * where a specific virtual node selection on a router-targeting route is silently
 * dropped and never communicated to the inner router.
 */
@Plugin(configType = NodeForIdRouterFactory.Config.class)
public class NodeForIdRouterFactory
        implements RouterFactory<NodeForIdRouterFactory.Config, NodeForIdRouterFactory.Config> {

    public record Config(int virtualNodeId) {}

    @Override
    public Config initialize(RouterFactoryContext context, Config config) {
        return config;
    }

    @Override
    public Router createRouter(RouterFactoryContext context, Config config) {
        return new Router() {
            @Override
            public CompletionStage<RouterResponse> onRequest(ApiKeys apiKey,
                                                             short apiVersion,
                                                             RequestHeaderData header,
                                                             ApiMessage request,
                                                             RouterContext ctx) {
                VirtualNode node = ctx.nodeForId(config.virtualNodeId());
                return ctx.sendRequest(node, header, request)
                        .thenCompose(body -> ctx.respondWith(body).completed());
            }
        };
    }
}

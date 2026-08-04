/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.it;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.protocol.ApiKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;

import io.kroxylicious.it.testplugins.router.ContextCapturingRouterFactory;
import io.kroxylicious.it.testplugins.router.NodeForIdRouterFactory;
import io.kroxylicious.proxy.config.ClusterDefinition;
import io.kroxylicious.proxy.config.ConfigurationBuilder;
import io.kroxylicious.proxy.config.RouteDefinition;
import io.kroxylicious.proxy.config.RouteTarget;
import io.kroxylicious.proxy.config.RouterDefinition;
import io.kroxylicious.proxy.config.VirtualClusterBuilder;
import io.kroxylicious.proxy.internal.config.Feature;
import io.kroxylicious.proxy.internal.config.Features;
import io.kroxylicious.proxy.topology.VirtualNode;
import io.kroxylicious.testing.integration.tester.KroxyliciousTesters;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;
import io.kroxylicious.testing.kafka.junit5ext.Topic;

import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.baseConfigurationBuilder;
import static io.kroxylicious.testing.integration.tester.KroxyliciousConfigUtils.defaultPortIdentifiesNodeGatewayBuilder;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the contract violation where a router's {@code nodeForId(V)} +
 * {@code sendRequest(specificNode, ...)} call on a router-targeting route silently
 * drops the selected virtual node ID. The inner router never learns which specific
 * virtual node the outer router intended.
 *
 * <p>Setup:
 * <pre>
 * VirtualCluster → outer router (NodeForIdRouterFactory)
 *   route "to-inner" (id=0, targets inner router)
 *     → inner router (ContextCapturingRouterFactory)
 *         route "backend" (id=0, targets cluster)
 * </pre>
 *
 * <p>The outer router calls {@code nodeForId(0)} for every request. Given the outer
 * router has a single route "to-inner" (IdentityNodeIdMapping), {@code nodeForId(0)}
 * resolves to {@code VirtualNode(route="to-inner", targetNodeId=0)}, and
 * {@code sendRequest(specificNode, ...)} dispatches via
 * {@code sendToSpecificNode(0, "to-inner", ...)}.
 *
 * <p>METADATA requests arrive over the bootstrap connection, where
 * {@link io.kroxylicious.proxy.internal.routing.NestedRoutingHandler} is constructed
 * with {@code nodeId=null}. Under correct behaviour, the handler should propagate the
 * target virtual node ID (0) from the frame into the inner
 * {@link io.kroxylicious.proxy.router.RouterContext}, making
 * {@link io.kroxylicious.proxy.router.RouterContext#virtualNode()} return
 * {@code Optional.of(VirtualNode("backend", 0))}. With the current bug the target node
 * is dropped: the inner {@code RouterContext} receives {@code endpointVirtualNodeId=null}
 * and {@code virtualNode()} returns {@code Optional.empty()}.
 */
@ExtendWith(KafkaClusterExtension.class)
@ExtendWith(NettyLeakDetectorExtension.class)
class NodeAffinityContractIT {

    private static final Features ROUTING_ENABLED = Features.builder().enable(Feature.ROUTING).build();

    static KafkaCluster cluster;

    @BeforeEach
    void setUp() {
        ContextCapturingRouterFactory.reset();
    }

    @AfterEach
    void tearDown() {
        ContextCapturingRouterFactory.reset();
    }

    private ConfigurationBuilder nodeAffinityConfig() {
        var target = new ClusterDefinition("backend-cluster", cluster.getBootstrapServers(), null);

        var innerRoute = new RouteDefinition("backend", 0, List.of(), new RouteTarget("backend-cluster", null));
        var innerRouter = new RouterDefinition("inner",
                ContextCapturingRouterFactory.class.getName(),
                new ContextCapturingRouterFactory.Config("backend"),
                List.of(innerRoute));

        var outerRoute = new RouteDefinition("to-inner", 0, List.of(), new RouteTarget(null, "inner"));
        var outerRouter = new RouterDefinition("outer",
                NodeForIdRouterFactory.class.getName(),
                new NodeForIdRouterFactory.Config(0),
                List.of(outerRoute));

        var vc = new VirtualClusterBuilder()
                .withName("demo")
                .withTarget(new RouteTarget(null, "outer"))
                .addToGateways(defaultPortIdentifiesNodeGatewayBuilder("localhost:9292").build())
                .build();

        return baseConfigurationBuilder()
                .addToClusterDefinitions(target)
                .addToRouterDefinitions(outerRouter, innerRouter)
                .addToVirtualClusters(vc);
    }

    @Test
    void nodeForIdSelectionOnRouterTargetingRouteShouldBeVisibleToInnerRouterOnBootstrapConnection(Topic topic)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Given
        // Capture virtualNode() only for METADATA requests, which arrive over the
        // bootstrap connection (endpointVirtualNodeId=null in NestedRoutingHandler).
        // This is the unambiguous case: with the bug the target node ID is dropped
        // so virtualNode() is always Optional.empty(); with the fix it is Optional.present().
        var capturedVirtualNode = new AtomicReference<Optional<VirtualNode>>();
        ContextCapturingRouterFactory.currentAction.set((apiKey, apiVersion, header, request, ctx) -> {
            if (apiKey == ApiKeys.METADATA) {
                capturedVirtualNode.set(ctx.virtualNode());
            }
            return ctx.sendRequest(ctx.anyNode("backend"), header, request)
                    .thenCompose(body -> ctx.respondWith(body).completed());
        });
        var config = nodeAffinityConfig();

        // When
        try (var tester = KroxyliciousTesters.newBuilder(config).setFeatures(ROUTING_ENABLED).createDefaultKroxyliciousTester();
                var producer = tester.producer(Map.of(
                        "enable.idempotence", false,
                        "retries", 0,
                        "batch.size", 0,
                        "linger.ms", 0))) {
            producer.send(new ProducerRecord<>(topic.name(), "k", "v")).get(10, TimeUnit.SECONDS);
        }

        // Then
        assertThat(capturedVirtualNode.get())
                .as("inner router should observe the virtual node selected by the outer router's nodeForId(0) call "
                        + "on the METADATA (bootstrap) request; Optional.empty() means the target node ID was "
                        + "silently dropped at the nesting boundary — known bug in RouterDispatchHandler.doSendToSpecificNode")
                .isNotNull()
                .isPresent();
    }
}

/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.proxy.service.NodeIdentificationStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualClusterListenerTest {

    @Mock
    PortIdentifiesNodeIdentificationStrategy portIdentifiesNode;

    @Mock
    SniHostIdentifiesNodeIdentificationStrategy sniHostIdentifiesNode;

    @Mock
    NodeIdentificationStrategy nodeIdentificationStrategy;

    @Test
    void rejectsMissing() {
        var empty = Optional.<Tls> empty();

        assertThatThrownBy(() -> new VirtualClusterGateway("name", null, null, empty))
                .isInstanceOf(IllegalConfigurationException.class);
    }

    @Test
    void rejectsBoth() {
        var empty = Optional.<Tls> empty();

        assertThatThrownBy(() -> new VirtualClusterGateway("name", portIdentifiesNode, sniHostIdentifiesNode, empty))
                .isInstanceOf(IllegalConfigurationException.class);
    }

    @Test
    void rejectsSniHostIdentifiesNodeWithoutTls() {
        var empty = Optional.<Tls> empty();

        assertThatThrownBy(() -> new VirtualClusterGateway("name", null, sniHostIdentifiesNode, empty))
                .isInstanceOf(IllegalConfigurationException.class);
    }

    @Test
    void acceptsPortIdentifiesNode() {
        var empty = Optional.<Tls> empty();

        var listener = new VirtualClusterGateway("name", portIdentifiesNode, null, empty);
        assertThat(listener).isNotNull();
    }

    @Test
    void acceptsSniHostIdentifiesNode() {
        var tls = Optional.of(new Tls(null, null, null, null));

        var listener = new VirtualClusterGateway("name", null, sniHostIdentifiesNode, tls);
        assertThat(listener).isNotNull();
    }

    @Test
    void createsPortIdentifiesProvider() {
        when(portIdentifiesNode.buildStrategy(anyString())).thenReturn(nodeIdentificationStrategy);
        var listener = new VirtualClusterGateway("name", portIdentifiesNode, null, Optional.empty());
        var strategy = listener.buildNodeIdentificationStrategy("cluster");
        assertThat(strategy).isEqualTo(this.nodeIdentificationStrategy);
    }

    @Test
    void createsSniHostIdentifiesProvider() {
        var tls = Optional.of(new Tls(null, null, null, null));
        when(sniHostIdentifiesNode.buildStrategy(anyString())).thenReturn(nodeIdentificationStrategy);
        var listener = new VirtualClusterGateway("name", null, sniHostIdentifiesNode, tls);
        var strategy = listener.buildNodeIdentificationStrategy("cluster");
        assertThat(strategy).isEqualTo(this.nodeIdentificationStrategy);
    }
}

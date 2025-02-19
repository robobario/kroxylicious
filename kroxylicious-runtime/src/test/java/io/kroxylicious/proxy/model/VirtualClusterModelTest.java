/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.model;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

import io.kroxylicious.proxy.config.IllegalConfigurationException;
import io.kroxylicious.proxy.config.TargetCluster;
import io.kroxylicious.proxy.config.secret.InlinePassword;
import io.kroxylicious.proxy.config.tls.KeyPair;
import io.kroxylicious.proxy.config.tls.ServerOptions;
import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.proxy.config.tls.TlsClientAuth;
import io.kroxylicious.proxy.config.tls.TlsTestConstants;
import io.kroxylicious.proxy.config.tls.TrustStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

class VirtualClusterModelTest {

    private static final InlinePassword PASSWORD_PROVIDER = new InlinePassword("storepass");

    private static final String KNOWN_CIPHER_SUITE;

    static {
        try {
            var defaultSSLParameters = SSLContext.getDefault().getDefaultSSLParameters();
            KNOWN_CIPHER_SUITE = defaultSSLParameters.getCipherSuites()[0];
            assertThat(KNOWN_CIPHER_SUITE).isNotNull();
            assertThat(defaultSSLParameters.getProtocols()).contains("TLSv1.2", "TLSv1.3");
        }
        catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private String client;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() {
        String privateKeyFile = TlsTestConstants.getResourceLocationOnFilesystem("server.key");
        String cert = TlsTestConstants.getResourceLocationOnFilesystem("server.crt");
        client = TlsTestConstants.getResourceLocationOnFilesystem("client.jks");
        keyPair = new KeyPair(privateKeyFile, cert, null);
    }


    static Stream<Arguments> clientAuthSettings() {
        return Stream.of(
                argumentSet("don't expect client side auth",
                        TlsClientAuth.NONE,
                        (Consumer<SSLEngine>) (SSLEngine sslEngine) -> {
                            assertThat(sslEngine.getWantClientAuth()).isFalse();
                            assertThat(sslEngine.getNeedClientAuth()).isFalse();
                        }),
                argumentSet("want client side auth",
                        TlsClientAuth.REQUESTED,
                        (Consumer<SSLEngine>) (SSLEngine sslEngine) -> {
                            assertThat(sslEngine.getWantClientAuth()).isTrue();
                            assertThat(sslEngine.getNeedClientAuth()).isFalse();
                        }),
                argumentSet("need client side auth",
                        TlsClientAuth.REQUIRED,
                        (Consumer<SSLEngine>) (SSLEngine sslEngine) -> {
                            assertThat(sslEngine.getWantClientAuth()).isFalse();
                            assertThat(sslEngine.getNeedClientAuth()).isTrue();
                        }));
    }


    @Test
    void shouldNotAllowUpstreamToProvideTlsServerOptions() {
        // Given
        final Optional<Tls> downstreamTls = Optional
                .of(new Tls(keyPair, new TrustStore(client, PASSWORD_PROVIDER, null, new ServerOptions(TlsClientAuth.REQUIRED)), null, null));
        final TargetCluster targetCluster = new TargetCluster("bootstrap:9092", downstreamTls);

        // When/Then
        assertThatThrownBy(() -> new VirtualClusterModel("wibble", targetCluster, false, false, List.of()))
                .isInstanceOf(IllegalConfigurationException.class)
                .hasMessageContaining("Cannot apply trust options");
    }

}

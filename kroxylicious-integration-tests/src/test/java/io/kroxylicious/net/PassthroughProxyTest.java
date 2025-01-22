/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

public class PassthroughProxyTest {

    public static final String BODY = "hello";

    @Test
    public void testProxy() throws Exception {
        WireMockServer wireMockServer = new WireMockServer();
        wireMockServer.stubFor(WireMock.get(urlEqualTo("/")).willReturn(WireMock.aResponse().withBody(BODY)));
        wireMockServer.start();
        int mockPort = wireMockServer.port();
        try (var proxy = new PassthroughProxy(mockPort, "localhost");
                var httpClient = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://localhost:" + proxy.getLocalPort());
            HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(send.body()).isEqualTo(BODY);
        }
        finally {
            wireMockServer.shutdown();
        }
    }
}

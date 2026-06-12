/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.router;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RouterTest {

    private static final RouterResponse STUB_RESULT = new RouterResponse() {
    };

    private final Router router = new Router() {
        @Override
        public CompletionStage<RouterResponse> onRequest(short apiVersion, ApiKeys apiKey,
                                                         RequestHeaderData header, ApiMessage request,
                                                         RouterContext context) {
            return CompletableFuture.completedFuture(STUB_RESULT);
        }
    };

    @Test
    void defaultCloseIsNoOp() {
        assertThatCode(router::close).doesNotThrowAnyException();
    }

    @Test
    void defaultStaticRoutesReturnsEmptyMap() {
        assertThat(router.staticRoutes()).isEmpty();
    }
}

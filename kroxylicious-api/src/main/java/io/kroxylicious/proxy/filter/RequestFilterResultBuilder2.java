/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;

public class RequestFilterResultBuilder2<T extends ApiMessage> {

    public static class BaseRequestFilterResultBuilder2<T extends ApiMessage> {
        private final RequestHeaderData headers;
        private final T message;
        private final ApiMessage shortCircuit;

        private boolean closeConnection = false;

        public BaseRequestFilterResultBuilder2(RequestHeaderData headers, T message, ApiMessage shortCircuit) {
            this.headers = headers;
            this.message = message;
            this.shortCircuit = shortCircuit;
        }

        public BaseRequestFilterResultBuilder2<T> withCloseConnection(boolean closeConnection) {
            this.closeConnection = closeConnection;
            return this;
        }

        public RequestFilterResult<T> build() {
            return new RequestFilterResult<>() {
                @Override
                public ApiMessage shortCircuitResponse() {
                    return shortCircuit;
                }

                @Override
                public ApiMessage header() {
                    return headers;
                }

                @Override
                public T message() {
                    return message;
                }

                @Override
                public boolean closeConnection() {
                    return closeConnection;
                }
            };
        }
    }

    public BaseRequestFilterResultBuilder2<T> forward(RequestHeaderData headers, T message) {
        return new BaseRequestFilterResultBuilder2<>(headers, message, null);
    }

    public BaseRequestFilterResultBuilder2<T> shortCircuitRespond(ApiMessage message) {
        return new BaseRequestFilterResultBuilder2<>(null, null, message);
    }

}

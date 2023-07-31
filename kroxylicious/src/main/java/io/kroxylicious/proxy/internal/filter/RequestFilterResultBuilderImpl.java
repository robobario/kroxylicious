/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;

import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.RequestFilterResultBuilder;

public class RequestFilterResultBuilderImpl<T extends ApiMessage> extends FilterResultBuilderImpl<RequestFilterResultBuilder<T>, RequestFilterResult<T>, T>
        implements RequestFilterResultBuilder<T> {

    private static final String REQUEST_DATA_NAME_SUFFIX = "RequestData";
    private static final String RESPONSE_DATA_NAME_SUFFIX = "ResponseData";
    private boolean shortCircuitResponse;
    private ApiMessage shortCircuitResponseMessage;

    public RequestFilterResultBuilderImpl() {
    }

    @Override
    protected void validateMessage(ApiMessage message) {
        super.validateMessage(message);
        var expectedClassNameSuffix = shortCircuitResponse ? RESPONSE_DATA_NAME_SUFFIX : REQUEST_DATA_NAME_SUFFIX;
        if (message != null && !message.getClass().getSimpleName().endsWith(expectedClassNameSuffix)) {
            throw new IllegalArgumentException("class name " + message.getClass().getName() + " does not have expected suffix " + expectedClassNameSuffix);
        }
    }

    @Override
    protected void validateHeader(ApiMessage header) {
        super.validateHeader(header);
        var expectedInterface = shortCircuitResponse ? ResponseHeaderData.class : RequestHeaderData.class;
        if (header != null && !expectedInterface.isInstance(header)) {
            throw new IllegalArgumentException("header " + header.getClass().getName() + " does not implement expected class " + ResponseHeaderData.class.getName());

        }
    }

    @Override
    public RequestFilterResultBuilder<T> withShortCircuitResponse(ApiMessage response) {
        var expectedClassNameSuffix = RESPONSE_DATA_NAME_SUFFIX;
        if (response != null && !response.getClass().getSimpleName().endsWith(expectedClassNameSuffix)) {
            throw new IllegalArgumentException("class name " + response.getClass().getName() + " does not have expected suffix " + expectedClassNameSuffix);
        }
        if (this.message() != null) {
            throw new IllegalStateException("cannot call asRequestShortCircuitResponse after message has been assigned");
        }
        if (this.header() != null) {
            throw new IllegalStateException("cannot call asRequestShortCircuitResponse after header has been assigned");
        }
        this.shortCircuitResponse = true;
        this.shortCircuitResponseMessage = response;
        return this;
    }

    @Override
    public RequestFilterResult<T> build() {
        return new RequestFilterResult<>() {

            @Override
            public ApiMessage shortCircuitResponse() {
                return shortCircuitResponse ? shortCircuitResponseMessage : null;
            }

            @Override
            public ApiMessage header() {
                return RequestFilterResultBuilderImpl.this.header();
            }

            @Override
            public T message() {
                return RequestFilterResultBuilderImpl.this.message();
            }

            @Override
            public boolean closeConnection() {
                return RequestFilterResultBuilderImpl.this.closeConnection();
            }
        };

    }
}

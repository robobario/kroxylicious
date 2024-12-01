/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import io.kroxylicious.test.Response;
import io.kroxylicious.test.codec.ByteBufAccessorImpl;
import io.kroxylicious.test.codec.OpaqueRequestFrame;
import io.kroxylicious.test.tester.KroxyliciousConfigUtils;

import edu.umd.cs.findbugs.annotations.NonNull;

import static io.kroxylicious.test.tester.KroxyliciousTesters.mockKafkaKroxyliciousTester;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * In addition to the behaviour tested by {@link ApiVersionsIT} we also need to handle the client
 * sending ApiVersions requests at an apiVersion higher than the proxy understands. With no proxy
 * involved, the behaviour is the broker will respond with a v0 apiversions response and the error
 * code is set to UNSUPPORTED_VERSION and the api_versions array populated with the supported versions
 * for ApiVersions, so that the client can retry with the highest supported ApiVersions version.
 * </p>
 * <br>
 * In this case the proxy will respond with it's own v0 response populated with the proxies supported
 * ApiVersions versions. It may be that on the following request the upstream broker will be behind
 * the proxy and respond with it's own UNSUPPORTED_VERSION response. So the clients must be able to
 * tolerate 2 UNSUPPORTED_VERSION responses in a row.
 * </p>
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class ApiVersionsDowngradeIT {

    public static final int CORRELATION_ID = 100;
    public static final short API_VERSIONS_ID = ApiKeys.API_VERSIONS.id;

    @Test
    void clientAheadOfProxy() {
        try (var tester = mockKafkaKroxyliciousTester(KroxyliciousConfigUtils::proxy);
                var client = tester.simpleTestClient()) {
            OpaqueRequestFrame frame = createHypotheticalFutureRequest();
            CompletableFuture<Response> responseCompletableFuture = client.get(
                    frame);
            assertThat(responseCompletableFuture).succeedsWithin(5, TimeUnit.SECONDS).satisfies(response -> {
                assertThat(response.payload().apiKeys()).isEqualTo(ApiKeys.API_VERSIONS);
                assertThat(response.payload().message()).isInstanceOfSatisfying(ApiVersionsResponseData.class, data -> {
                    assertThat(data.errorCode()).isEqualTo(Errors.UNSUPPORTED_VERSION.code());
                    ApiVersionsResponseData.ApiVersion expected = new ApiVersionsResponseData.ApiVersion();
                    expected.setApiKey(ApiKeys.API_VERSIONS.id).setMinVersion(ApiKeys.API_VERSIONS.oldestVersion())
                            .setMaxVersion(ApiKeys.API_VERSIONS.latestVersion(true));
                    ApiVersionsResponseData.ApiVersionCollection collection = new ApiVersionsResponseData.ApiVersionCollection();
                    collection.add(expected);
                    assertThat(data.apiKeys()).isEqualTo(collection);
                });
            });
            assertThat(tester.getReceivedRequestCount()).isZero();
        }
    }

    private static @NonNull OpaqueRequestFrame createHypotheticalFutureRequest() {
        short unsupportedVersion = (short) (ApiKeys.API_VERSIONS.latestVersion(true) + 1);
        RequestHeaderData requestHeaderData = getRequestHeaderData(API_VERSIONS_ID, unsupportedVersion, CORRELATION_ID);
        short requestHeaderVersion = ApiKeys.API_VERSIONS.requestHeaderVersion(unsupportedVersion);
        ObjectSerializationCache cache = new ObjectSerializationCache();
        int headerSize = requestHeaderData.size(cache, requestHeaderVersion);
        int bodySize = 4;
        int messageSize = headerSize + bodySize;
        ByteBuf buffer = Unpooled.buffer(messageSize, messageSize);
        ByteBufAccessorImpl accessor = new ByteBufAccessorImpl(buffer);
        requestHeaderData.write(accessor, cache, requestHeaderVersion);
        // the proxy can assume that it is safe to read the latest header version from the message, but any
        // bytes after than cannot be read because the future message may be using a new header version with
        // additional fields. So the bytes after the latest known header bytes are arbitrary as far as the proxy
        // is concerned.
        int arbitraryBodyData = Integer.MAX_VALUE;
        accessor.writeInt(arbitraryBodyData);
        return new OpaqueRequestFrame(buffer, CORRELATION_ID, messageSize, true, ApiKeys.API_VERSIONS, unsupportedVersion);
    }

    private static @NonNull RequestHeaderData getRequestHeaderData(short apiKey, short unsupportedVersion, int correlationId) {
        RequestHeaderData requestHeaderData = new RequestHeaderData();
        requestHeaderData.setRequestApiKey(apiKey);
        requestHeaderData.setRequestApiVersion(unsupportedVersion);
        requestHeaderData.setCorrelationId(correlationId);
        return requestHeaderData;
    }

}

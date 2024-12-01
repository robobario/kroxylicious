/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.codec;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.apache.kafka.common.protocol.types.RawTaggedField;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;

import edu.umd.cs.findbugs.annotations.NonNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaRequestDecoderTest {

    @Test
    void decodeUnknownApiVersions() {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new KafkaRequestDecoder(RequestDecoderTest.DECODE_EVERYTHING, 1024));
        RequestHeaderData header = latestVersionHeaderWithAllFields(ApiKeys.API_VERSIONS, Short.MAX_VALUE);
        int arbitraryBodyBytes = Integer.MAX_VALUE;
        ObjectSerializationCache cache = new ObjectSerializationCache();
        short latestApiVersion = ApiKeys.API_VERSIONS.latestVersion(true);
        short requestHeaderVersion = ApiKeys.API_VERSIONS.requestHeaderVersion(latestApiVersion);
        int headerSize = header.size(cache, requestHeaderVersion);
        int messageSize = headerSize + 4;
        ByteBuf buffer = Unpooled.buffer();
        ByteBufAccessorImpl accessor = new ByteBufAccessorImpl(buffer);
        accessor.writeInt(messageSize);
        header.write(accessor, cache, requestHeaderVersion);
        accessor.writeInt(arbitraryBodyBytes);
        embeddedChannel.writeInbound(buffer);
        Object inboundMessage = embeddedChannel.readInbound();
        assertThat(inboundMessage).isInstanceOfSatisfying(DecodedRequestFrame.class, decodedRequestFrame -> {
            assertThat(decodedRequestFrame.correlationId()).isEqualTo(2);
            assertThat(decodedRequestFrame.apiKey()).isEqualTo(ApiKeys.API_VERSIONS);
            assertThat(decodedRequestFrame.apiVersion()).isEqualTo((short) 0);
            assertThat(decodedRequestFrame.decodeResponse()).isTrue();
            assertThat(decodedRequestFrame.hasResponse()).isTrue();
            assertThat(decodedRequestFrame.header()).isInstanceOfSatisfying(DowngradeRequestHeaderData.class, requestHeaderData -> {
                assertThat(requestHeaderData.correlationId()).isEqualTo(2);
                assertThat(requestHeaderData.requestApiKey()).isEqualTo(ApiKeys.API_VERSIONS.id);
                assertThat(requestHeaderData.requestApiVersion()).isEqualTo((short) 0);
                assertThat(requestHeaderData.clientId()).isEmpty();
                assertThat(requestHeaderData.unknownTaggedFields()).isEmpty();
                short version = ApiKeys.API_VERSIONS.requestHeaderVersion((short) 0);
                assertUnwritable(requestHeaderData, cache, version);
            });
            assertThat(decodedRequestFrame.body()).isInstanceOfSatisfying(ApiVersionsRequestData.class, apiVersionsRequestData -> {
                assertUnwritable(apiVersionsRequestData, cache, (short) 0);
            });
        });
    }

    // after ApiVersions negotiation we should never encounter a request from the client for an api version unknown to the proxy
    @Test
    void throwsOnUnsupportedVersionOfNonApiVersionsRequests() {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new KafkaRequestDecoder(RequestDecoderTest.DECODE_EVERYTHING, 1024));
        short maxSupportedVersion = ApiKeys.METADATA.latestVersion(true);
        short unsupportedVersion = (short) (maxSupportedVersion + 1);
        RequestHeaderData header = latestVersionHeaderWithAllFields(ApiKeys.METADATA, unsupportedVersion);
        int arbitraryBodyBytes = Integer.MAX_VALUE;
        ObjectSerializationCache cache = new ObjectSerializationCache();
        short requestHeaderVersion = ApiKeys.METADATA.requestHeaderVersion(maxSupportedVersion);
        int headerSize = header.size(cache, requestHeaderVersion);
        int messageSize = headerSize + 4;
        ByteBuf buffer = Unpooled.buffer();
        ByteBufAccessorImpl accessor = new ByteBufAccessorImpl(buffer);
        accessor.writeInt(messageSize);
        header.write(accessor, cache, requestHeaderVersion);
        accessor.writeInt(arbitraryBodyBytes);
        assertThatThrownBy(() -> {
            embeddedChannel.writeInbound(buffer);
        }).isInstanceOf(DecoderException.class).cause().isInstanceOf(IllegalStateException.class)
                .hasMessage("client apiVersion %d ahead of proxy maximum %d for api key: METADATA", unsupportedVersion, maxSupportedVersion);
    }

    private static @NonNull RequestHeaderData latestVersionHeaderWithAllFields(ApiKeys requestApiKey, short requestApiVersion) {
        RequestHeaderData header = new RequestHeaderData();
        header.setRequestApiKey(requestApiKey.id);
        header.setRequestApiVersion(requestApiVersion);
        header.setCorrelationId(2);
        header.setClientId("clientId");
        header.unknownTaggedFields().add(new RawTaggedField(5, "arbitrary".getBytes(StandardCharsets.UTF_8)));
        return header;
    }

    private static void assertUnwritable(ApiMessage requestHeaderData, ObjectSerializationCache cache, short version) {
        assertThatThrownBy(() -> requestHeaderData.size(cache, version)).isInstanceOf(UnsupportedOperationException.class);
        ByteBufAccessorImpl byteBufAccessor = new ByteBufAccessorImpl(Unpooled.buffer());
        assertThatThrownBy(() -> requestHeaderData.write(byteBufAccessor, cache, version)).isInstanceOf(UnsupportedOperationException.class);
    }

}

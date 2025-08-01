/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import io.kroxylicious.proxy.frame.DecodedResponseFrame;
import io.kroxylicious.proxy.frame.OpaqueResponseFrame;

import edu.umd.cs.findbugs.annotations.NonNull;

import static io.kroxylicious.proxy.internal.codec.ByteBufs.writeByteBuf;
import static io.kroxylicious.proxy.model.VirtualClusterModel.DEFAULT_SOCKET_FRAME_MAX_SIZE_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResponseDecoderTest extends AbstractCodecTest {

    private CorrelationManager mgr;
    private KafkaResponseDecoder responseDecoder;
    private int frameMaxSizeBytes;

    @BeforeEach
    void setup() {
        mgr = new CorrelationManager(12);
        frameMaxSizeBytes = DEFAULT_SOCKET_FRAME_MAX_SIZE_BYTES;
        responseDecoder = createResponseDecoder(mgr, frameMaxSizeBytes);
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsExactlyOneFrame_decoded(short apiVersion) {
        mgr.putBrokerRequest(ApiKeys.API_VERSIONS.id, apiVersion, 52, true, null, null, true);
        assertEquals(52, exactlyOneFrame_decoded(apiVersion,
                ApiKeys.API_VERSIONS::responseHeaderVersion,
                v -> AbstractCodecTest.exampleResponseHeader(),
                AbstractCodecTest::exampleApiVersionsResponse,
                AbstractCodecTest::deserializeResponseHeaderUsingKafkaApis,
                AbstractCodecTest::deserializeApiVersionsResponseUsingKafkaApis,
                responseDecoder,
                DecodedResponseFrame.class,
                header -> header.setCorrelationId(12), false),
                "Unexpected correlation id");
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsExactlyOneFrame_opaque(short apiVersion) throws Exception {
        mgr.putBrokerRequest(ApiKeys.API_VERSIONS.id, apiVersion, 52, true, null, null, false);
        assertEquals(52, exactlyOneFrame_encoded(apiVersion,
                ApiKeys.API_VERSIONS::responseHeaderVersion,
                v -> AbstractCodecTest.exampleResponseHeader(),
                AbstractCodecTest::exampleApiVersionsResponse,
                responseDecoder,
                OpaqueResponseFrame.class, false),
                "Unexpected correlation id");
    }

    @Test
    void shouldFireListenerOnDecode() {
        // Given
        var correlationManager = mock(CorrelationManager.class);
        when(correlationManager.getBrokerCorrelation(anyInt())).thenReturn(mock());

        KafkaMessageListener listener = mock(KafkaMessageListener.class);

        ResponseHeaderData exampleHeader = exampleResponseHeader();
        ApiVersionsResponseData exampleBody = exampleApiVersionsResponse();
        short apiVersion = ApiKeys.API_VERSIONS.latestVersion();
        short headerVersion = ApiKeys.API_VERSIONS.responseHeaderVersion(apiVersion);
        ByteBuffer response = serializeUsingKafkaApis(headerVersion, exampleHeader, apiVersion, exampleBody);
        int expectedSizeIncludingLength = response.remaining();
        var decoder = new KafkaResponseDecoder(correlationManager, Integer.MAX_VALUE, listener);

        // When
        decoder.decode(null, Unpooled.wrappedBuffer(response), new ArrayList<>());

        // Then
        verify(listener).onMessage(isA(OpaqueResponseFrame.class), eq(expectedSizeIncludingLength));
    }

    @NonNull
    private static KafkaResponseDecoder createResponseDecoder(CorrelationManager mgr, int socketFrameMaxSizeBytes) {
        return new KafkaResponseDecoder(mgr, socketFrameMaxSizeBytes, null);
    }

    @Test
    void shouldThrowIfFirstIntGreaterThanMaxFrameSize() {
        // given
        int sentMaxSizeBytes = frameMaxSizeBytes + 1;
        ByteBuf buffer = toLength5ByteBuf(sentMaxSizeBytes);
        Assertions.assertThatThrownBy(() -> {
            // when
            responseDecoder.decode(null, buffer, new ArrayList<>());
        }).isInstanceOfSatisfying(FrameOversizedException.class, e -> {
            // then
            assertThat(e.getMaxFrameSizeBytes()).isEqualTo(frameMaxSizeBytes);
            assertThat(e.getReceivedFrameSizeBytes()).isEqualTo(sentMaxSizeBytes);
        });
    }

    @Test
    void shouldNotThrowIfFirstIntLessThanMaxFrameSize() {
        // given
        ByteBuf buffer = toLength5ByteBuf(frameMaxSizeBytes - 1);
        int readerIndexAtStart = buffer.readerIndex();
        ArrayList<Object> objects = new ArrayList<>();

        // when
        responseDecoder.decode(null, buffer, objects);

        // then
        assertThat(objects).isEmpty();
        assertThat(buffer.readerIndex()).isEqualTo(readerIndexAtStart);
    }

    @Test
    void shouldNotThrowIfFirstIntEqualToMaxFrameSize() {
        // given
        ByteBuf buffer = toLength5ByteBuf(frameMaxSizeBytes);
        int readerIndexAtStart = buffer.readerIndex();
        ArrayList<Object> objects = new ArrayList<>();

        // when
        responseDecoder.decode(null, buffer, objects);

        // then
        assertThat(objects).isEmpty();
        assertThat(buffer.readerIndex()).isEqualTo(readerIndexAtStart);
    }

    // we need 5 bytes in the buffer for the decoder to read the length out and act on it
    private static ByteBuf toLength5ByteBuf(int i) {
        return writeByteBuf(outputStream -> {
            outputStream.writeInt(i);
            outputStream.writeByte(1);
        });
    }

    @Test
    void supportsFallbackToApiResponseV0() {
        mgr.putBrokerRequest(ApiKeys.API_VERSIONS.id, (short) 3, 52, true, null, null, true);

        // given
        ByteBuf buffer = Unpooled.wrappedBuffer(serializeUsingKafkaApis((short) 0,
                exampleResponseHeader(),
                (short) 0,
                new ApiVersionsResponseData()
                        .setErrorCode(Errors.UNSUPPORTED_VERSION.code())));
        List<Object> objects = new ArrayList<>();

        // when
        responseDecoder.decode(null, buffer, objects);

        // then
        assertThat(objects)
                .singleElement()
                .extracting("body")
                .isEqualTo(new ApiVersionsResponseData().setErrorCode(Errors.UNSUPPORTED_VERSION.code()));
    }

    @Test
    void throwsOnUndecodableV0ApiVersionsResponse() throws IOException {
        int upstreamCorrelationId = mgr.putBrokerRequest(ApiKeys.API_VERSIONS.id, (short) 0, 52, true, null, null, true);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        dataOutputStream.writeInt(4); // framesize
        dataOutputStream.writeInt(upstreamCorrelationId);
        dataOutputStream.close();
        // given
        ByteBuf buffer = Unpooled.wrappedBuffer(byteArrayOutputStream.toByteArray());
        List<Object> objects = new ArrayList<>();

        // when
        assertThatThrownBy(() -> {
            responseDecoder.decode(null, buffer, objects);
        }).isInstanceOf(IndexOutOfBoundsException.class);

    }

    @Test
    void throwsOnUndecodableNonV0ApiVersionsResponse() throws IOException {
        int upstreamCorrelationId = mgr.putBrokerRequest(ApiKeys.API_VERSIONS.id, (short) 3, 52, true, null, null, true);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        dataOutputStream.writeInt(4); // framesize
        dataOutputStream.writeInt(upstreamCorrelationId);
        dataOutputStream.close();
        // given
        ByteBuf buffer = Unpooled.wrappedBuffer(byteArrayOutputStream.toByteArray());
        List<Object> objects = new ArrayList<>();

        // then
        assertThatThrownBy(() -> {
            responseDecoder.decode(null, buffer, objects);
        }).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
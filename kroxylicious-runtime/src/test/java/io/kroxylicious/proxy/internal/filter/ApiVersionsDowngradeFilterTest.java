/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.junit.jupiter.api.Test;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;
import io.kroxylicious.proxy.internal.FilterHarness;
import io.kroxylicious.proxy.internal.codec.DowngradeApiVersionsRequestData;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVersionsDowngradeFilterTest extends FilterHarness {

    @Test
    void shortCircuitDowngradeApiVersionsRequests() {
        buildChannel(new ApiVersionsDowngradeFilter(a -> ApiKeys.API_VERSIONS.latestVersion(true)));
        writeRequest(DowngradeApiVersionsRequestData.downgradeApiVersionsFrame(5));
        DecodedResponseFrame<ApiVersionsResponseData> response = channel.readInbound();
        assertThat(response.body()).isInstanceOfSatisfying(ApiVersionsResponseData.class, apiVersionsResponseData -> {
            assertThat(apiVersionsResponseData.errorCode()).isEqualTo(Errors.UNSUPPORTED_VERSION.code());
            ApiVersionsResponseData.ApiVersion apiVersion = new ApiVersionsResponseData.ApiVersion();
            apiVersion.setApiKey(ApiKeys.API_VERSIONS.id);
            apiVersion.setMinVersion(ApiKeys.API_VERSIONS.oldestVersion());
            apiVersion.setMaxVersion(ApiKeys.API_VERSIONS.latestVersion(true));
            assertThat(apiVersionsResponseData.apiKeys()).containsExactly(apiVersion);
        });
    }

    @Test
    void passThroughAnythingElse() {
        buildChannel(new ApiVersionsDowngradeFilter(a -> ApiKeys.API_VERSIONS.latestVersion(true)));
        DecodedRequestFrame<ApiVersionsRequestData> request = writeRequest(new ApiVersionsRequestData());
        var propagated = channel.readOutbound();
        assertThat(propagated).isEqualTo(request);
    }

}

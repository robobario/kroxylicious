/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion;
import org.apache.kafka.common.protocol.ApiKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.NonNull;

public class ApiVersionsServiceImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiVersionsServiceImpl.class);
    private final Function<ApiKeys, Short> apiKeysShortFunction;

    public ApiVersionsServiceImpl(@NonNull Function<ApiKeys, Short> apiKeysShortFunction) {
        Objects.requireNonNull(apiKeysShortFunction);
        this.apiKeysShortFunction = apiKeysShortFunction;
    }

    public void updateVersions(String channel, ApiVersionsResponseData apiVersionsResponse) {
        intersectApiVersions(channel, apiVersionsResponse, apiKeysShortFunction);
    }

    private static void intersectApiVersions(String channel, ApiVersionsResponseData resp, Function<ApiKeys, Short> apiKeysShortFunction) {
        Set<ApiVersion> unknownApis = new HashSet<>();
        for (var key : resp.apiKeys()) {
            short apiId = key.apiKey();
            if (ApiKeys.hasId(apiId)) {
                ApiKeys apiKey = ApiKeys.forId(apiId);
                intersectApiVersion(channel, key, apiKey, apiKeysShortFunction);
            }
            else {
                unknownApis.add(key);
            }
        }
        resp.apiKeys().removeAll(unknownApis);
    }

    /**
     * Update the given {@code key}'s max and min versions so that the client uses APIs versions mutually
     * understood by both the proxy and the broker.
     *
     * @param channel The channel.
     * @param key The key data from an upstream API_VERSIONS response.
     * @param apiKey The proxy's API key for this API.
     * @param apiKeysShortFunction
     */
    private static void intersectApiVersion(String channel, ApiVersion key, ApiKeys apiKey, Function<ApiKeys, Short> apiKeysShortFunction) {
        short mutualMin = (short) Math.max(
                key.minVersion(),
                apiKey.messageType.lowestSupportedVersion());
        if (mutualMin != key.minVersion()) {
            LOGGER.trace("{}: {} min version changed to {} (was: {})", channel, apiKey, mutualMin, key.maxVersion());
            key.setMinVersion(mutualMin);
        }
        else {
            LOGGER.trace("{}: {} min version unchanged (is: {})", channel, apiKey, mutualMin);
        }

        short mutualMax = (short) Math.min(
                key.maxVersion(),
                apiKeysShortFunction.apply(apiKey));
        if (mutualMax != key.maxVersion()) {
            LOGGER.trace("{}: {} max version changed to {} (was: {})", channel, apiKey, mutualMin, key.maxVersion());
            key.setMaxVersion(mutualMax);
        }
        else {
            LOGGER.trace("{}: {} max version unchanged (is: {})", channel, apiKey, mutualMin);
        }
    }

}

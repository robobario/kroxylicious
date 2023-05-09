/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.schema.config;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ApicurioJsonSchemaConfig {

    private final String apicurioRegistryUrl;
    private final Map<String, Object> registryConfiguration;

    @JsonCreator
    public ApicurioJsonSchemaConfig(@JsonProperty(value = "registryUrl", required = true) String registryUrl,
                                    @JsonProperty("registryConfiguration") Map<String, Object> registryConfiguration) {
        this.apicurioRegistryUrl = registryUrl;
        this.registryConfiguration = registryConfiguration == null ? Map.of() : registryConfiguration;
    }

    public Map<String, Object> getRegistryConfiguration() {
        return registryConfiguration;
    }

    public String getApicurioRegistryUrl() {
        return apicurioRegistryUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ApicurioJsonSchemaConfig that = (ApicurioJsonSchemaConfig) o;
        return Objects.equals(apicurioRegistryUrl, that.apicurioRegistryUrl) && Objects.equals(registryConfiguration, that.registryConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apicurioRegistryUrl, registryConfiguration);
    }

    @Override
    public String toString() {
        return "ApicurioJsonSchemaValidationRule{" +
                "apicurioRegistryUrl='" + apicurioRegistryUrl + '\'' +
                ", registryConfiguration=" + registryConfiguration +
                '}';
    }
}

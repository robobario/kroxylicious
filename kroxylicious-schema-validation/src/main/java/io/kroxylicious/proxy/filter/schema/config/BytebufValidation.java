/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.schema.config;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for validating a Bytebuffer holding a value.
 */
public class BytebufValidation {
    private final SyntacticallyCorrectJsonConfig syntacticallyCorrectJsonConfig;
    private final ApicurioJsonSchemaConfig apicurioJsonSchemaConfig;
    private final boolean allowNulls;
    private final boolean allowEmpty;

    /**
     * Create a new BytebufValidation
     *
     * @param syntacticallyCorrectJsonConfig   optional configuration, if non-null indicates ByteBuffer should contain syntactically correct JSON
     * @param allowNulls                       whether a null byte-buffer should be considered valid
     * @param allowEmpty                       whether an empty byte-buffer should be considered valid
     * @param apicurioJsonSchemaConfig         optional configuration, if non-null indicates ByteBuffer should contain JSON encoded with apicurio JSON schema
     */
    @JsonCreator
    public BytebufValidation(@JsonProperty("syntacticallyCorrectJson") SyntacticallyCorrectJsonConfig syntacticallyCorrectJsonConfig,
                             @JsonProperty(value = "allowNulls", defaultValue = "true") Boolean allowNulls,
                             @JsonProperty(value = "allowEmpty", defaultValue = "false") Boolean allowEmpty,
                             @JsonProperty("apicurioJsonSchema") ApicurioJsonSchemaConfig apicurioJsonSchemaConfig) {
        this.syntacticallyCorrectJsonConfig = syntacticallyCorrectJsonConfig;
        this.apicurioJsonSchemaConfig = apicurioJsonSchemaConfig;
        this.allowNulls = allowNulls == null || allowNulls;
        this.allowEmpty = allowEmpty != null && allowEmpty;
    }

    /**
     * Get syntactically correct json config
     * @return optional containing syntacticallyCorrectJsonConfig if non-null, empty otherwise
     */
    public Optional<SyntacticallyCorrectJsonConfig> getSyntacticallyCorrectJsonConfig() {
        return Optional.ofNullable(syntacticallyCorrectJsonConfig);
    }

    /**
     * Get apicurio json schema config
     * @return optional containing apicurioJsonSchemaConfig if non-null, empty otherwise
     */
    public Optional<ApicurioJsonSchemaConfig> getApicurioJsonSchemaConfig() {
        return Optional.ofNullable(apicurioJsonSchemaConfig);
    }

    /**
     * Are buffers valid if they are null on the ${@link org.apache.kafka.common.record.Record}
     * @return allowNulls
     */
    public boolean isAllowNulls() {
        return allowNulls;
    }

    /**
     * Are buffers valid if they are empty (non-null, 0 length) on the ${@link org.apache.kafka.common.record.Record}
     * @return allowEmpty
     */
    public boolean isAllowEmpty() {
        return allowEmpty;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BytebufValidation that = (BytebufValidation) o;
        return allowNulls == that.allowNulls && allowEmpty == that.allowEmpty && Objects.equals(syntacticallyCorrectJsonConfig,
                that.syntacticallyCorrectJsonConfig) && Objects.equals(apicurioJsonSchemaConfig, that.apicurioJsonSchemaConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(syntacticallyCorrectJsonConfig, apicurioJsonSchemaConfig, allowNulls, allowEmpty);
    }

    @Override
    public String toString() {
        return "BytebufValidation{" +
                "syntacticallyCorrectJsonConfig=" + syntacticallyCorrectJsonConfig +
                ", apicurioJsonSchemaConfig=" + apicurioJsonSchemaConfig +
                ", allowNulls=" + allowNulls +
                ", allowEmpty=" + allowEmpty +
                '}';
    }
}

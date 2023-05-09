/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.schema.validation.bytebuf;

import java.util.Map;

/**
 * Static factory methods for creating/getting ${@link BytebufValidator} instances
 */
public class BytebufValidators {

    private BytebufValidators() {

    }

    private static final AllValidBytebufValidator ALL_VALID = new AllValidBytebufValidator();

    /**
     * get validator that validates all {@link java.nio.ByteBuffer}s
     * @return validator
     */
    public static BytebufValidator allValid() {
        return ALL_VALID;
    }

    /**
     * get validator that validates null/empty {@link java.nio.ByteBuffer}s and then delegates non-null/non-empty {@link java.nio.ByteBuffer}s to a delegate
     * @param nullValid are null buffers valide\
     * @param emptyValid are empty buffers valid
     * @param delegate delegate to call if buffer is non-null/non-empty
     * @return validator
     */
    public static BytebufValidator nullEmptyValidator(boolean nullValid, boolean emptyValid, BytebufValidator delegate) {
        return new NullEmptyBytebufValidator(nullValid, emptyValid, delegate);
    }

    /**
     * Get validator that validates if a ByteBuf conforms to a JsonSchema stored in an apicurio registry. Currently, it is assumed that
     * the buffer will contain a magic byte followed by 8 bytes which make up the schema identifier.
     * @param registryUrl url of the apicurio registry
     * @param apicurioConfiguration further apicurio configuration, such as security configuration for connections to the registry
     * @return validator
     */
    public static BytebufValidator apicurioJsonSchemaValidator(String registryUrl, Map<String, Object> apicurioConfiguration) {
        return new ApicurioJsonSchemaBytebufValidator(registryUrl, apicurioConfiguration);
    }

    /**
     * get validator that validates if a non-null/non-empty buffer contains syntactically correct JSON
     * @param validateObjectKeysUnique optionally check if JSON Objects contain unique keys
     * @return validator
     */
    public static BytebufValidator jsonSyntaxValidator(boolean validateObjectKeysUnique) {
        return new JsonSyntaxBytebufValidator(validateObjectKeysUnique);
    }
}

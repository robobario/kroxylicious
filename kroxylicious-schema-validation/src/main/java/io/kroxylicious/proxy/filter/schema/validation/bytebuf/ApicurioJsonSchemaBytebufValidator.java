/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.schema.validation.bytebuf;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.Record;

import io.apicurio.registry.resolver.SchemaResolverConfig;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.serde.AbstractKafkaDeserializer;
import io.apicurio.registry.serde.DefaultIdHandler;
import io.apicurio.registry.serde.fallback.DefaultFallbackArtifactProvider;
import io.apicurio.registry.serde.fallback.FallbackArtifactProvider;
import io.apicurio.registry.serde.headers.DefaultHeadersHandler;
import io.apicurio.registry.serde.headers.HeadersHandler;
import io.apicurio.schema.validation.json.JsonValidationResult;
import io.apicurio.schema.validation.json.JsonValidator;

import io.kroxylicious.proxy.filter.schema.validation.Result;

class ApicurioJsonSchemaBytebufValidator implements BytebufValidator {
    private final String registryUrl;
    private final Map<String, Object> apicurioConfiguration;
    private final ArtifactReference fallbackArtifactRef;
    boolean enableHeaders = true;
    private final Map<ArtifactReference, JsonValidator> schemaValidators = new HashMap<>();
    private final DefaultIdHandler idHandler;
    private final HeadersHandler headersHandler;

    ApicurioJsonSchemaBytebufValidator(String registryUrl, Map<String, Object> apicurioConfiguration) {
        this.registryUrl = registryUrl;
        this.apicurioConfiguration = apicurioConfiguration;
        idHandler = new DefaultIdHandler();
        headersHandler = new DefaultHeadersHandler();
        headersHandler.configure(apicurioConfiguration, false);
        FallbackArtifactProvider fallbackArtifactProvider = new DefaultFallbackArtifactProvider();
        fallbackArtifactProvider.configure(apicurioConfiguration, false);
        fallbackArtifactRef = fallbackArtifactProvider.get(null, null, null);
    }

    // TODO support header-based artifactReference lookups
    @Override
    public Result validate(ByteBuffer buffer, int length, Record record, boolean isKey) {
        try {
            int bodyLength;
            if (!enableHeaders) {
                if (length < idHandler.idSize() + 1) {
                    return new Result(false, "buffer is not large enough to contain id and magic byte");
                }
                byte magicByte = buffer.get();
                if (magicByte != AbstractKafkaDeserializer.MAGIC_BYTE) {
                    return new Result(false, "first byte of buffer was not magic byte");
                }
                bodyLength = length - idHandler.idSize() - 1;
            }
            else {
                bodyLength = length;
            }
            ArtifactReference artifactReference = getArtifactReference(buffer, idHandler, new RecordHeaders(record.headers()));
            JsonValidator jsonValidator = schemaValidators.computeIfAbsent(artifactReference, this::createJsonValidator);

            byte[] bytes = new byte[bodyLength];
            // to avoid this copy we could create an InputStream implementation for doing a size-limited read from a ByteBuffer
            buffer.get(bytes);
            JsonValidationResult validationResult = jsonValidator.validateByArtifactReference(bytes);
            return validationResult.success() ? Result.VALID : new Result(false, validationResult.toString());
        }
        catch (Exception e) {
            return new Result(false, "Exception while attempting to validate with apicurio JSON schema: " + e.getMessage());
        }
    }

    private ArtifactReference getArtifactReference(ByteBuffer buffer, DefaultIdHandler idHandler, Headers headers) {
        try {
            return getReferenceFromMessage(buffer, idHandler, headers);
        }
        catch (Exception e) {
            return fallbackArtifactRef;
        }
    }

    private ArtifactReference getReferenceFromMessage(ByteBuffer buffer, DefaultIdHandler idHandler, Headers headers) {
        if (enableHeaders) {
            ArtifactReference artifactReference = headersHandler.readHeaders(headers);
            if (artifactReference.getArtifactId() == null) {
                throw new IllegalArgumentException("failed to extract required apicurio schema artifactId from message headers");
            }
            return artifactReference;
        }
        else {
            return idHandler.readId(buffer);
        }
    }

    private JsonValidator createJsonValidator(ArtifactReference artifactReference) {
        Map<String, Object> props = new HashMap<>();
        props.putIfAbsent(SchemaResolverConfig.REGISTRY_URL, registryUrl);
        props.putAll(apicurioConfiguration);
        return new JsonValidator(props, Optional.ofNullable(artifactReference));
    }
}

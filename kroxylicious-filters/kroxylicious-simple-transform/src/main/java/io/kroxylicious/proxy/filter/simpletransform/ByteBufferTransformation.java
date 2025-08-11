/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter.simpletransform;

import java.nio.ByteBuffer;

import org.apache.kafka.common.Uuid;

/**
 * A transformation of the key or value of a produce record.
 */
@FunctionalInterface
public interface ByteBufferTransformation {
    ByteBuffer transform(String topicName, ByteBuffer original, Uuid topicId);
}
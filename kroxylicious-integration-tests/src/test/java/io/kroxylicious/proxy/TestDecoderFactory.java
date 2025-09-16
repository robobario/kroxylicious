/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import java.nio.ByteBuffer;

import org.apache.kafka.common.Uuid;

import io.kroxylicious.proxy.filter.simpletransform.ByteBufferTransformation;
import io.kroxylicious.proxy.filter.simpletransform.ByteBufferTransformationFactory;
import io.kroxylicious.proxy.plugin.Plugin;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;

@Plugin(configType = Void.class)
public class TestDecoderFactory implements ByteBufferTransformationFactory<Void> {

    @Override
    public void validateConfiguration(Void config) throws PluginConfigurationException {

    }

    @Override
    public TestDecoder createTransformation(Void configuration) {
        return new TestDecoder();
    }

    public static class TestDecoder implements ByteBufferTransformation {

        @Override
        public ByteBuffer transform(String topicName, ByteBuffer in, Uuid topicId) {
            String topicNameOrId = topicName.equals("") ? topicId.toString() : topicName;
            return FilterIT.decode(topicNameOrId, in);
        }
    }
}

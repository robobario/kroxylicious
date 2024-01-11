/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.micrometer.core.instrument.Metrics;

import io.kroxylicious.filter.encryption.inband.BufferPool;
import io.kroxylicious.filter.encryption.inband.InBandKeyManager;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.plugin.Plugin;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;
import io.kroxylicious.proxy.plugin.PluginImplConfig;
import io.kroxylicious.proxy.plugin.PluginImplName;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link FilterFactory} for {@link EnvelopeEncryptionFilter}.
 * @param <K> The key reference
 * @param <E> The type of encrypted DEK
 */
@Plugin(configType = EnvelopeEncryption.Config.class)
public class EnvelopeEncryption<K, E> implements FilterFactory<EnvelopeEncryption.Config, EnvelopeEncryption.EventLoopLocalResourceFactory<K, E>> {

    private static KmsMetrics kmsMetrics = MicrometerKmsMetrics.create(Metrics.globalRegistry);

    record Config(
                  @JsonProperty(required = true) @PluginImplName(KmsService.class) String kms,
                  @PluginImplConfig(implNameProperty = "kms") Object kmsConfig,

                  @JsonProperty(required = true) @PluginImplName(KekSelectorService.class) String selector,
                  @PluginImplConfig(implNameProperty = "selector") Object selectorConfig) {

    }

    @Override
    public EventLoopLocalResourceFactory<K, E> initialize(FilterFactoryContext context, Config config) throws PluginConfigurationException {
        return new EventLoopLocalResourceFactory<>(config);
    }

    @NonNull
    @Override
    @SuppressWarnings("java:S2245") // secure randomization not needed for exponential backoff
    public EnvelopeEncryptionFilter<K> createFilter(FilterFactoryContext context, EventLoopLocalResourceFactory<K, E> configuration) {
        return new EnvelopeEncryptionFilter<>(configuration.getKeyManager(context), configuration.getKekSelector(context));
    }

    /**
     * This class is responsible for constructing and holding resources for each netty event loop.
     * This means multiple channels on the same event loop will share encryption/decryption resources,
     * so we will utilise the DEKs we generate better. The KeyContexts will last longer than the
     * span of one connection.
     * By having a KeyManager per eventloop, on the hot path we should only have one thread using
     * encryption/decryption resources. So there should be little locking waiting for resources
     * like the KeyManager. But since the KMS APIs are asynchronous we will still have to take care
     * that access is thread-safe, either switching back to the eventloop or continuing to carefully
     * synchronise access to the unsafe structures like KeyContext.
     * @param <K>
     * @param <E>
     */
    public static class EventLoopLocalResourceFactory<K, E> {

        private final Config configuration;
        private final Map<ScheduledExecutorService, Kms<K, E>> kms = Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<ScheduledExecutorService, KeyManager<K>> keyManagers = Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<ScheduledExecutorService, TopicNameBasedKekSelector<K>> kekSelectors = Collections.synchronizedMap(new IdentityHashMap<>());

        public EventLoopLocalResourceFactory(Config configuration) {
            this.configuration = configuration;
        }

        KeyManager<K> getKeyManager(FilterFactoryContext context) {
            if (context.eventLoop() == null) {
                throw new IllegalArgumentException("eventLoop is null");
            }
            return keyManagers.computeIfAbsent(context.eventLoop(), o -> {
                Kms<K, E> kms = getKms(context);
                return new InBandKeyManager<>(kms, BufferPool.allocating(), 500_000, o,
                        new ExponentialJitterBackoffStrategy(Duration.ofMillis(500), Duration.ofSeconds(5), 2d, ThreadLocalRandom.current()));
            });
        }

        TopicNameBasedKekSelector<K> getKekSelector(FilterFactoryContext context) {
            if (context.eventLoop() == null) {
                throw new IllegalArgumentException("eventLoop is null");
            }
            return kekSelectors.computeIfAbsent(context.eventLoop(), o -> {
                Kms<K, E> kms = getKms(context);
                KekSelectorService<Object, K> ksPlugin = context.pluginInstance(KekSelectorService.class, configuration.selector());
                return ksPlugin.buildSelector(kms, configuration.selectorConfig());
            });
        }

        Kms<K, E> getKms(FilterFactoryContext context) {
            if (context.eventLoop() == null) {
                throw new IllegalArgumentException("eventLoop is null");
            }
            return kms.computeIfAbsent(context.eventLoop(), o -> {
                KmsService<Object, K, E> kmsPlugin = context.pluginInstance(KmsService.class, configuration.kms());
                Kms<K, E> kms = kmsPlugin.buildKms(configuration.kmsConfig());
                return InstrumentedKms.instrument(kms, kmsMetrics);
            });
        }

    }
}

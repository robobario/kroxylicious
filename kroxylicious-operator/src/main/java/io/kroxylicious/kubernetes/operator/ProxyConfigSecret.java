/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.kubernetes.operator;

import java.util.Map;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;

/**
 * A Kube {@code Secret} containing the proxy config YAML.
 * We use a {@code Secret} (rather than a {@code ConfigMap})
 * because the config might contain sensitive settings like passwords
 */
@KubernetesDependent
public class ProxyConfigSecret
        extends CRUDKubernetesDependentResource<Secret, KafkaProxy> {

    /**
     * The key of the {@code config.yaml} entry in the desired {@code Secret}.
     */
    public static final String CONFIG_YAML_KEY = "config.yaml";

    public ProxyConfigSecret() {
        super(Secret.class);
    }

    /**
     * @return The {@code metadata.name} of the desired Secret {@code Secret}.
     */
    static String secretName(KafkaProxy primary) {
        return primary.getMetadata().getName();
    }

    @Override
    protected Secret desired(KafkaProxy primary,
                             Context<KafkaProxy> context) {

        return new SecretBuilder()
                .editOrNewMetadata()

                .withName(secretName(primary))
                .withNamespace(primary.getMetadata().getNamespace())
                .endMetadata()
                .withStringData(Map.of(CONFIG_YAML_KEY,
                        """
                                adminHttp:
                                  endpoints:
                                    prometheus: {}
                                virtualClusters:
                                  demo:
                                    targetCluster:
                                      bootstrap_servers: TARGET_BOOTSTRAP_SERVERS
                                    clusterNetworkAddressConfigProvider:
                                      type: PortPerBrokerClusterNetworkAddressConfigProvider
                                      config:
                                        bootstrapAddress: localhost:9292
                                        brokerAddressPattern: BROKER_ADDRESS_PATTERN
                                    logNetwork: false
                                    logFrames: false
                                """
                                .replace(
                                        "TARGET_BOOTSTRAP_SERVERS", primary.getSpec().getBootstrapServers())
                                .replace("BROKER_ADDRESS_PATTERN", ProxyService.serviceName(primary))))
                .build();
    }
}
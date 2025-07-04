/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.templates.kroxylicious;

import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyBuilder;

public class KroxyliciousKafkaProxyTemplates {

    private KroxyliciousKafkaProxyTemplates() {
    }

    /**
     * Default kafka proxy CR.
     *
     * @param namespaceName the namespace name
     * @param name the name
     * @param replicas the number proxy pods to deploy
     * @return the kafka proxy builder
     */
    public static KafkaProxyBuilder defaultKafkaProxyCR(String namespaceName, String name, int replicas) {
        // @formatter:off
        return new KafkaProxyBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespaceName)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                .endSpec();
        // @formatter:on
    }
}

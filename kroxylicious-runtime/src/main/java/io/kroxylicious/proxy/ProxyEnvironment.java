/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import io.kroxylicious.proxy.config.Configuration;

import edu.umd.cs.findbugs.annotations.NonNull;

public enum ProxyEnvironment {
    PRODUCTION {
        @Override
        public void validate(@NonNull Configuration config) {
            if (config.internal() != null && config.internal().isPresent()) {
                throw new IllegalStateException("internal configuration for proxy present in production mode");
            }
        }
    },
    DEVELOPMENT;

    public void validate(Configuration config) {
    }
}

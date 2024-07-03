/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.service;

public record KekRef<K>(K kekId, String version) {
    public static <K> KekRef<K> unversioned(K kekId) {
        return new KekRef<>(kekId, "UNVERSIONED");
    }
}

/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.dek;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

import javax.annotation.concurrent.ThreadSafe;

import io.kroxylicious.kms.service.DestroyableRawSecretKey;
import io.kroxylicious.kms.service.KekRef;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.kms.service.Serde;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A DekManager encapsulates a {@link Kms}, providing access to the ability to
 * encrypt and decrypt using key material from the KMS without exposing
 * that key material outside this package.
 * @param <K> The type of KEK id
 * @param <E> The type of encrypted DEK
 */
@ThreadSafe
public class DekManager<K, E> {

    private final Kms<K, E> kms;
    private final long maxEncryptionsPerDek;

    public <C> DekManager(KmsService<C, K, E> kmsService, C config, long maxEncryptionsPerDek) {
        this.kms = kmsService.buildKms(config);
        this.maxEncryptionsPerDek = maxEncryptionsPerDek;
    }

    /**
     * @return The KMS's serde for encrypted DEKs
     * @see Kms#edekSerde()
     */
    public Serde<E> edekSerde() {
        return kms.edekSerde();
    }

    /**
     * Result a key alias
     * @see Kms#resolveAlias(String)
     * @param alias
     * @return
     */
    public CompletionStage<KekRef<K>> resolveAlias(String alias) {
        return kms.resolveAliasToKekRef(alias);
    }

    /**
     * Generate a fresh DEK from the KMS, wrapping it in a {@link Dek}.
     * The returned DEK can only be used for both encryption and decryption, but only for the given cipher.
     * @param kekRef The KEK id
     * @param cipherManager The cipher supported by the returned DEK.
     * @return A completion state that completes with the {@link Dek}, or
     * fails if the request to the KMS fails.
     */
    public CompletionStage<Dek<E>> generateDek(@NonNull K kekRef, @NonNull CipherManager cipherManager) {
        Objects.requireNonNull(kekRef);
        Objects.requireNonNull(cipherManager);
        return kms.generateDekPair(kekRef)
                .thenApply(dekPair -> new Dek<>(dekPair.edek(), DestroyableRawSecretKey.toDestroyableKey(dekPair.dek()), cipherManager, maxEncryptionsPerDek));
    }

    /**
     * Ask the KMS to decrypt an encrypted DEK, returning a {@link Dek}.
     * The returned DEK can only be used for decryption, and only for the given cipher.
     * @param edek The encrypted DEK
     * @param cipherManager The cipher supported by the returned DEK.
     * @return A completion stage that completes with the {@link Dek}, or
     * fails if the request to the KMS fails.
     */
    public CompletionStage<Dek<E>> decryptEdek(@NonNull E edek, @NonNull CipherManager cipherManager) {
        Objects.requireNonNull(edek);
        Objects.requireNonNull(cipherManager);
        return kms.decryptEdek(edek).thenApply(key -> new Dek<>(edek, DestroyableRawSecretKey.toDestroyableKey(key), cipherManager, 0));
    }
}

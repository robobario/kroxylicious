/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import io.kroxylicious.filter.encryption.config.RecordField;
import io.kroxylicious.filter.encryption.encrypt.EncryptionScheme;
import io.kroxylicious.kms.service.KekRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionSchemeTest {

    @Test
    void shouldRejectInvalidConstructorArgs() {
        EnumSet<RecordField> nonEmpty = EnumSet.of(RecordField.RECORD_VALUE);
        var empty = EnumSet.noneOf(RecordField.class);
        assertThrows(NullPointerException.class, () -> new EncryptionScheme<>(null, nonEmpty));
        Object kekId = new Object();
        assertThrows(NullPointerException.class, () -> new EncryptionScheme<>(KekRef.unversioned(kekId), null));
        assertThrows(IllegalArgumentException.class, () -> new EncryptionScheme<>(KekRef.unversioned(kekId), empty));
    }

    @Test
    void shouldAcceptValidConstructorArgs() {
        EnumSet<RecordField> nonEmpty = EnumSet.of(RecordField.RECORD_VALUE);
        Object kekId = new Object();
        KekRef<Object> kekRef = KekRef.unversioned(kekId);
        var es = new EncryptionScheme<>(kekRef, nonEmpty);
        assertEquals(kekRef, es.kekId());
        assertEquals(nonEmpty, es.recordFields());
    }

}

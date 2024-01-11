/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.filter.FilterFactoryContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnvelopeEncryptionTest {

    public static final ScheduledExecutorService EXECUTOR_SERVICE = Executors.newScheduledThreadPool(1);

    @Test
    void shouldInitAndCreateFilter() {
        EnvelopeEncryption.Config config = new EnvelopeEncryption.Config("KMS", null, "SELECTOR", null);
        var ee = new EnvelopeEncryption<>();
        var fc = mock(FilterFactoryContext.class);
        var kmsService = mock(KmsService.class);
        var kms = mock(Kms.class);
        var kekSelectorService = mock(KekSelectorService.class);
        var kekSelector = mock(TopicNameBasedKekSelector.class);

        doReturn(kmsService).when(fc).pluginInstance(KmsService.class, "KMS");
        doReturn(kms).when(kmsService).buildKms(any());

        doReturn(kekSelectorService).when(fc).pluginInstance(KekSelectorService.class, "SELECTOR");
        doReturn(kekSelector).when(kekSelectorService).buildSelector(any(), any());
        when(fc.eventLoop()).thenReturn(EXECUTOR_SERVICE);

        ee.initialize(fc, config);
        var filter = ee.createFilter(fc, new EnvelopeEncryption.EventLoopLocalResourceFactory(config));
        assertNotNull(filter);
    }

}

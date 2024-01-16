/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.records;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

import static io.kroxylicious.filter.encryption.records.BatchAwareMemoryRecordsBuilder.EMPTY_HEADERS;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncRecordTransformTest {

    public static final IncrementOffset INCREMENT_OFFSET = new IncrementOffset();

    @Test
    public void testAsyncTransform() {
        var mrb = new BatchAwareMemoryRecordsBuilder(new ByteBufferOutputStream(1));
        mrb.addBatch(CompressionType.NONE, TimestampType.CREATE_TIME, 4L);
        for (long offset = 4L; offset < 1000L; offset++) {
            mrb.appendWithOffset(offset, 123, "a".getBytes(StandardCharsets.UTF_8), null, EMPTY_HEADERS);
        }
        var mr1 = mrb.build();

        ExecutorService executorService = Executors.newSingleThreadExecutor();

        CompletableFuture<MemoryRecords> memoryRecordsCompletableFuture = AsyncRecordTransform.transformRecordsPreservingBatches(mr1.batchIterator(),
                record -> CompletableFuture.supplyAsync(IncrementOffset::new, executorService));

        assertThat(memoryRecordsCompletableFuture).succeedsWithin(Duration.ofSeconds(50));

        MemoryRecords records = memoryRecordsCompletableFuture.join();
        Iterator<MutableRecordBatch> iterator = records.batches().iterator();
        MutableRecordBatch onlyBatch = iterator.next();
        assertThat(iterator).isExhausted();
        List<Record> actualRecords = StreamSupport.stream(onlyBatch.spliterator(), false).toList();
        assertThat(actualRecords).hasSize(996);
        assertThat(actualRecords.get(0).offset()).isEqualTo(6);
        assertThat(actualRecords.get(995).offset()).isEqualTo(1001);
    }

    // demonstrate it works if the CompletableFutures are always immediately completed
    @Test
    public void testAsyncTransformWithNoAsyncWork() {
        var mrb = new BatchAwareMemoryRecordsBuilder(new ByteBufferOutputStream(1));
        mrb.addBatch(CompressionType.NONE, TimestampType.CREATE_TIME, 4L);
        for (long offset = 4L; offset < 1000L; offset++) {
            mrb.appendWithOffset(offset, 123, "a".getBytes(StandardCharsets.UTF_8), null, EMPTY_HEADERS);
        }
        var mr1 = mrb.build();

        CompletableFuture<MemoryRecords> memoryRecordsCompletableFuture = AsyncRecordTransform.transformRecordsPreservingBatches(mr1.batchIterator(),
                record -> CompletableFuture.completedFuture(INCREMENT_OFFSET));

        assertThat(memoryRecordsCompletableFuture).succeedsWithin(Duration.ofSeconds(50));

        MemoryRecords records = memoryRecordsCompletableFuture.join();
        Iterator<MutableRecordBatch> iterator = records.batches().iterator();
        MutableRecordBatch onlyBatch = iterator.next();
        assertThat(iterator).isExhausted();
        List<Record> actualRecords = StreamSupport.stream(onlyBatch.spliterator(), false).toList();
        assertThat(actualRecords).hasSize(996);
        assertThat(actualRecords.get(0).offset()).isEqualTo(6);
        assertThat(actualRecords.get(995).offset()).isEqualTo(1001);
    }

    private static class IncrementOffset implements RecordTransform {
        @Override
        public void init(@NonNull Record record) {

        }

        @Override
        public long transformOffset(@NonNull Record record) {
            return record.offset() + 2;
        }

        @Override
        public long transformTimestamp(@NonNull Record record) {
            return record.timestamp();
        }

        @Nullable
        @Override
        public ByteBuffer transformKey(@NonNull Record record) {
            return record.key();
        }

        @Nullable
        @Override
        public ByteBuffer transformValue(@NonNull Record record) {
            return record.value();
        }

        @Nullable
        @Override
        public Header[] transformHeaders(@NonNull Record record) {
            return record.headers();
        }

        @Override
        public void resetAfterTransform(@NonNull Record record) {

        }
    }
}
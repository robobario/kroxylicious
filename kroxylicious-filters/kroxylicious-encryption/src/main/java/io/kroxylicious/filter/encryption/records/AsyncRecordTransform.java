/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.records;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.utils.ByteBufferOutputStream;

/**
 * The streaming API does not play with the CompletableFuture API, so if we wanted to do
 * asynchronous work per record then we would likely have to join within the stream, blocking the
 * calling thread. Since one eventloop thread could be serving many clients we don't want to block
 * it up.
 * This implementation demonstrates how we could support iterating over the records applying
 * an async transformation in order. If the work per-record goes async, then the iteration stops until the future
 * completes. This could be inefficient if your batch needs to go async for many records, but could
 * fit with our usages where we expect to be using mostly cached resources.
 */
public class AsyncRecordTransform {

    public static CompletableFuture<MemoryRecords> transformRecordsPreservingBatches(Iterator<MutableRecordBatch> batches, Function<Record, CompletableFuture<RecordTransform>> transform) {
        BatchAwareMemoryRecordsBuilder builder = new BatchAwareMemoryRecordsBuilder(new ByteBufferOutputStream(1000));
        return transformBatches(batches, transform, builder);
    }

    private static CompletableFuture<MemoryRecords> transformBatches(Iterator<MutableRecordBatch> batches, Function<Record, CompletableFuture<RecordTransform>> transform, BatchAwareMemoryRecordsBuilder builder) {
        if (!batches.hasNext()) {
           return CompletableFuture.completedFuture(builder.build());
        }
        CompletableFuture<Void> transformed = CompletableFuture.completedFuture(null);
        while (transformed.isDone() && batches.hasNext()) {
            if (transformed.isCompletedExceptionally()) {
                // the thenApply is never hit, this converts it to a failed future of MemoryRecords
                return transformed.thenApply(unused -> null);
            }
            MutableRecordBatch batch = batches.next();
            builder.addBatchLike(batch);
            Iterator<Record> records = batch.iterator();
            transformed = transformRecords(records, transform, builder);
        }
        return transformed.thenCompose(unused -> transformBatches(batches, transform, builder));
    }

    private static CompletableFuture<Void> transformRecords(Iterator<Record> records,
                                                            Function<Record, CompletableFuture<RecordTransform>> transform,
                                                            BatchAwareMemoryRecordsBuilder builder) {
        if (!records.hasNext()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> transformed = CompletableFuture.completedFuture(null);
        while (transformed.isDone() && records.hasNext()) {
            if (transformed.isCompletedExceptionally()) {
                return transformed;
            }
            Record next = records.next();
            transformed = transform.apply(next) // would need to get topic name and other features here
                    .thenAccept(recordTransform -> {
                        recordTransform.init(next);
                        builder.appendWithOffset(recordTransform.transformOffset(next), recordTransform.transformTimestamp(next), recordTransform.transformKey(next), recordTransform.transformValue(next), recordTransform.transformHeaders(next));
                        recordTransform.resetAfterTransform(next);
                    });
        }
        return transformed.thenCompose(unused -> transformRecords(records, transform, builder));
    }

}

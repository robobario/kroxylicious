package io.kroxylicious.proxy.internal.filter;

import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.ProduceRequestFilter;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.ByteBufferOutputStream;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;


public class KafkaInsightsFilter implements ProduceRequestFilter {

    public static class KafkaInsightsFilterConfig extends FilterConfig {
    }
    public static final int RECORDS_OFFSET = 61;

    @Override
    public void onProduceRequest(ProduceRequestData data, KrpcFilterContext context) {
        try {
            for (ProduceRequestData.TopicProduceData topicProduceData : data.topicData()) {
                for (ProduceRequestData.PartitionProduceData partitionData : topicProduceData.partitionData()) {
                    MemoryRecords records = (MemoryRecords) partitionData.records();
                    for (MutableRecordBatch batch : records.batches()) {
                        ByteBuffer buff = ByteBuffer.allocate(batch.sizeInBytes());
                        batch.writeTo(buff);
                        System.out.println("raw size: " + buff.position());
                        buff.position(RECORDS_OFFSET);
                        DataInputStream dataInputStream = new DataInputStream(batch.compressionType().wrapForInput(buff, batch.magic(), BufferSupplier.NO_CACHING));
                        byte[] uncompressedBytes = dataInputStream.readAllBytes();
                        System.out.println("uncompressed size: " + uncompressedBytes.length);
                        for (CompressionType compressionType : CompressionType.values()) {
                            ByteBufferOutputStream bufferStream = new ByteBufferOutputStream(uncompressedBytes.length);
                            OutputStream outputStream = compressionType.wrapForOutput(bufferStream, batch.magic());
                            outputStream.write(uncompressedBytes);
                            outputStream.close();
                            bufferStream.close();
                            ByteBuffer buffer = bufferStream.buffer();
                            System.out.println("" + compressionType.name + " size: " + buffer.position());
                        }
                    }
                }
            }
        } catch (Exception e){
            System.out.println("oh hell, it broke");
            e.printStackTrace();
        }
        context.forwardRequest(data);
    }
}

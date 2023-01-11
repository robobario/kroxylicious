/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.example.topicname;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.message.CreateTopicsResponseData;
import org.apache.kafka.common.message.DeleteTopicsResponseData;
import org.apache.kafka.common.message.MetadataResponseData;

import io.kroxylicious.proxy.filter.CreateTopicsResponseFilter;
import io.kroxylicious.proxy.filter.DeleteTopicsResponseFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.MetadataResponseFilter;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;

/**
 * Maintains a local mapping of topic id to topic name
 */
public class TopicNameFilter
        implements MetadataResponseFilter, DeleteTopicsResponseFilter, CreateTopicsResponseFilter {

    private final Map<Uuid, String> topicNames = new HashMap<>();

    @Override
    public void onMetadataResponse(DecodedResponseFrame<MetadataResponseData> response, KrpcFilterContext context) {
        MetadataResponseData responseData = response.body();
        if (responseData.topics() != null) {
            for (var topic : responseData.topics()) {
                topicNames.put(topic.topicId(), topic.name());
            }
        }
        // TODO how can we expose this state to other filters?
        // TODO filterContext.put("topicNames", topicNames);
        context.forwardResponse(responseData);
    }

    // We don't implement DeleteTopicsRequestFilter because we don't know whether
    // a delete topics request will succeed.
    @Override
    public void onDeleteTopicsResponse(DecodedResponseFrame<DeleteTopicsResponseData> response, KrpcFilterContext context) {
        DeleteTopicsResponseData body = response.body();
        for (var resp : body.responses()) {
            topicNames.remove(resp.topicId());
        }
        context.forwardResponse(body);
    }

    @Override
    public void onCreateTopicsResponse(DecodedResponseFrame<CreateTopicsResponseData> response, KrpcFilterContext context) {
        CreateTopicsResponseData body = response.body();
        for (var topic : body.topics()) {
            topicNames.put(topic.topicId(), topic.name());
        }
        context.forwardResponse(body);
    }
}

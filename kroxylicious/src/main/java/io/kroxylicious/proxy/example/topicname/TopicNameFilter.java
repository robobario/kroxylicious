/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.example.topicname;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.message.CreateTopicsResponseData;
import org.apache.kafka.common.message.DeleteTopicsResponseData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.ResponseHeaderData;

import io.kroxylicious.proxy.filter.CreateTopicsResponseFilter;
import io.kroxylicious.proxy.filter.DeleteTopicsResponseFilter;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.MetadataResponseFilter;
import io.kroxylicious.proxy.filter.ResponseFilterResult;

/**
 * Maintains a local mapping of topic id to topic name
 */
public class TopicNameFilter
        implements MetadataResponseFilter, DeleteTopicsResponseFilter, CreateTopicsResponseFilter {

    private final Map<Uuid, String> topicNames = new HashMap<>();

    @Override
    public CompletionStage<ResponseFilterResult> onMetadataResponse(short apiVersion, ResponseHeaderData header, MetadataResponseData response,
                                                                    KrpcFilterContext context) {
        if (response.topics() != null) {
            for (var topic : response.topics()) {
                topicNames.put(topic.topicId(), topic.name());
            }
        }
        // TODO how can we expose this state to other filters?
        // TODO filterContext.put("topicNames", topicNames);
        return context.completedForwardResponse(header, response);
    }

    // We don't implement DeleteTopicsRequestFilter because we don't know whether
    // a delete topics request will succeed.
    @Override
    public CompletionStage<ResponseFilterResult> onDeleteTopicsResponse(short apiVersion, ResponseHeaderData header, DeleteTopicsResponseData response,
                                                                        KrpcFilterContext context) {
        for (var resp : response.responses()) {
            topicNames.remove(resp.topicId());
        }
        return context.completedForwardResponse(header, response);
    }

    @Override
    public CompletionStage<ResponseFilterResult> onCreateTopicsResponse(short apiVersion, ResponseHeaderData header, CreateTopicsResponseData response,
                                                                        KrpcFilterContext context) {
        for (var topic : response.topics()) {
            topicNames.put(topic.topicId(), topic.name());
        }
        return context.completedForwardResponse(header, response);
    }
}

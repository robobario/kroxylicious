/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.authorization;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.DescribeProducersRequestData;
import org.apache.kafka.common.message.DescribeProducersRequestData.TopicRequest;
import org.apache.kafka.common.message.DescribeProducersResponseData;
import org.apache.kafka.common.message.DescribeProducersResponseData.PartitionResponse;
import org.apache.kafka.common.message.DescribeProducersResponseData.TopicResponse;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.Errors;

import io.kroxylicious.authorizer.service.Action;
import io.kroxylicious.authorizer.service.Decision;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;

class DescribeProducersEnforcement extends ApiEnforcement<DescribeProducersRequestData, DescribeProducersResponseData> {
    @Override
    short minSupportedVersion() {
        return 0;
    }

    @Override
    short maxSupportedVersion() {
        return 0;
    }

    @Override
    CompletionStage<RequestFilterResult> onRequest(RequestHeaderData header, DescribeProducersRequestData request, FilterContext context,
                                                   AuthorizationFilter authorizationFilter) {
        if (request.topics() == null || request.topics().isEmpty()) {
            return context.forwardRequest(header, request);
        }
        else {
            List<Action> actions = request.topics().stream()
                    .map(topic -> new Action(TopicResource.READ, topic.name())).toList();
            return authorizationFilter.authorization(context, actions).thenCompose(result -> {
                var partitioned = result.partition(request.topics(), TopicResource.READ,
                        TopicRequest::name);
                List<TopicRequest> denied = partitioned.get(Decision.DENY);
                if (!denied.isEmpty()) {
                    Map<String, Integer> firstIndexOfTopicNameInRequest = new HashMap<>();
                    for (int i = 0; i < request.topics().size(); i++) {
                        TopicRequest topicRequest = request.topics().get(i);
                        firstIndexOfTopicNameInRequest.putIfAbsent(topicRequest.name(), i);
                    }
                    request.topics().removeAll(denied);
                    authorizationFilter.pushInflightState(header, (DescribeProducersResponseData d) -> {
                        for (TopicRequest topicRequest : denied) {
                            TopicResponse topicResponse = new TopicResponse();
                            topicResponse.setName(topicRequest.name());
                            topicRequest.partitionIndexes().forEach(partitionIndex -> {
                                PartitionResponse response = new PartitionResponse();
                                response.setErrorCode(Errors.TOPIC_AUTHORIZATION_FAILED.code());
                                response.setErrorMessage(Errors.TOPIC_AUTHORIZATION_FAILED.message());
                                response.setPartitionIndex(partitionIndex);
                                topicResponse.partitions().add(response);
                            });
                            d.topics().add(topicResponse);
                        }
                        // preserve original order as much as possible
                        d.topics().sort(Comparator.comparingInt(o -> firstIndexOfTopicNameInRequest.getOrDefault(o.name(), -1)));
                        return d;
                    });
                }
                return context.forwardRequest(header, request);
            });
        }
    }
}

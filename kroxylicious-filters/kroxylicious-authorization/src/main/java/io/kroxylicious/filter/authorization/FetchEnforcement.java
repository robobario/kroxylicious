/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.authorization;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.Errors;

import io.kroxylicious.authorizer.service.Action;
import io.kroxylicious.authorizer.service.Decision;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;

import static org.apache.kafka.common.requests.FetchResponse.partitionResponse;

/**
 * Initially no support for topic ids
 */
class FetchEnforcement extends ApiEnforcement<FetchRequestData, FetchResponseData> {

    // lowest version supported by proxy
    short minSupportedVersion() {
        return 4;
    }

    short maxSupportedVersion() {
        return 12;
    }

    CompletionStage<RequestFilterResult> onRequest(RequestHeaderData header,
                                                   FetchRequestData request,
                                                   FilterContext context,
                                                   AuthorizationFilter authorizationFilter) {
        var topicReadActions = request.topics().stream()
                .map(t -> new Action(TopicResource.READ, t.topic()))
                .toList();
        return authorizationFilter.authorization(context, topicReadActions)
                .thenCompose(authorization -> {
                    var topicReadDecisions = request.topics().stream()
                            .collect(Collectors.groupingBy(t -> authorization.decision(TopicResource.READ, t.topic())));
                    List<FetchRequestData.FetchTopic> allowedTopics = topicReadDecisions.getOrDefault(Decision.ALLOW, List.of());
                    if (allowedTopics.isEmpty()) {
                        // Shortcircuit if there's no allowed topics
                        FetchResponseData response = new FetchResponseData();
                        response.setErrorCode(Errors.NONE.code());
                        var fetchableTopicResponses = createDenyTopicResponses(topicReadDecisions);
                        response.setResponses(fetchableTopicResponses);
                        return context.requestFilterResultBuilder()
                                .shortCircuitResponse(response)
                                .completed();
                    }
                    else if (topicReadDecisions.get(Decision.DENY).isEmpty()) {
                        // Just forward if there's no denied topics
                        return context.forwardRequest(header, request);
                    }
                    else {
                        List<String> requestTopicOrder = request.topics().stream().map(FetchRequestData.FetchTopic::topic).toList();
                        request.setTopics(allowedTopics);
                        var erroneousResponses = new HashMap<>(createDenyTopicResponses(topicReadDecisions).stream().collect(Collectors.toMap(
                                FetchResponseData.FetchableTopicResponse::topic, x -> x)));
                        authorizationFilter.pushInflightState(header,
                                (FetchResponseData response) -> combineDeniedWithUpstreamResponse(response, erroneousResponses, requestTopicOrder));
                        return context.forwardRequest(header, request);
                    }
                });
    }

    /**
     * Reimplements odd behaviour discovered in the broker where the order of successful/errored partitions influences what is emitted.
     * See {@link org.apache.kafka.common.requests.FetchResponse#toMessage(Errors, int, int, Iterator, List)}
     */
    private static FetchResponseData combineDeniedWithUpstreamResponse(FetchResponseData response,
                                                                       HashMap<String, FetchResponseData.FetchableTopicResponse> erroneousResponses,
                                                                       List<String> requestTopicOrder) {
        List<FetchResponseData.FetchableTopicResponse> responses = response.responses();
        List<FetchResponseData.FetchableTopicResponse> emptied = responses.stream().filter(fetchableTopicResponse -> {
            List<FetchResponseData.PartitionData> erroneous = fetchableTopicResponse.partitions().stream()
                    .filter(partitionData -> Errors.forCode(partitionData.errorCode()) != Errors.NONE).toList();
            if (!erroneous.isEmpty()) {
                fetchableTopicResponse.partitions().removeAll(erroneous);
                FetchResponseData.FetchableTopicResponse value = new FetchResponseData.FetchableTopicResponse();
                value.setTopic(fetchableTopicResponse.topic());
                value.setPartitions(erroneous);
                erroneousResponses.put(fetchableTopicResponse.topic(), value);
                return fetchableTopicResponse.partitions().isEmpty();
            }
            return false;
        }).toList();
        responses.removeAll(emptied); // remove any upstream topics that had all partitions respond erroneously (they will be re-added)
        for (String topic : requestTopicOrder) {
            if (erroneousResponses.containsKey(topic)) {
                if (responses.isEmpty()) {
                    responses.add(erroneousResponses.get(topic));
                }
                else if (responses.get(responses.size() - 1).topic().equals(topic)) {
                    responses.get(responses.size() - 1).partitions().addAll(erroneousResponses.get(topic).partitions());
                }
                else {
                    responses.add(erroneousResponses.get(topic));
                }
            }
        }
        return response;
    }

    private static List<FetchResponseData.FetchableTopicResponse> createDenyTopicResponses(
                                                                                           Map<Decision, List<FetchRequestData.FetchTopic>> topicReadDecisions) {
        return topicReadDecisions.get(Decision.DENY)
                .stream().map(t -> new FetchResponseData.FetchableTopicResponse()
                        .setTopic(t.topic())
                        .setTopicId(t.topicId())
                        .setPartitions(t.partitions().stream().map(p -> partitionResponse(p.partition(), Errors.TOPIC_AUTHORIZATION_FAILED)).toList()))
                .toList();
    }

}

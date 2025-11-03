/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.authorization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.JoinGroupRequestData;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.OffsetCommitRequestData;
import org.apache.kafka.common.message.OffsetCommitResponseData;
import org.apache.kafka.common.message.OffsetCommitResponseDataJsonConverter;
import org.apache.kafka.common.message.SyncGroupRequestData;
import org.apache.kafka.common.message.SyncGroupResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kroxylicious.filter.authorization.AuthorizationFilter;
import io.kroxylicious.test.client.KafkaClient;

import static org.assertj.core.api.Assertions.assertThat;

public class OffsetCommitAuthzIT extends AuthzIT {

    private static final String ALICE_TOPIC_NAME = "alice-topic";
    private static final String BOB_TOPIC_NAME = "bob-topic";
    private static final String EVE_TOPIC_NAME = "eve-topic";
    private static final String EXISTING_TOPIC_NAME = "existing-topic";
    public static final List<String> ALL_TOPIC_NAMES_IN_TEST = List.of(
            ALICE_TOPIC_NAME,
            BOB_TOPIC_NAME,
            EVE_TOPIC_NAME,
            EXISTING_TOPIC_NAME);

    private Path rulesFile;

    private List<AclBinding> aclBindings;
    private String memberId;

    @BeforeAll
    void beforeAll() throws IOException {
        // TODO need to add Carol who has Cluster.CREATE
        // TODO need to add Carol who has Cluster.CREATE
        rulesFile = Files.createTempFile(getClass().getName(), ".aclRules");
        Files.writeString(rulesFile, """
                version 1;
                import User from io.kroxylicious.proxy.authentication;
                import TopicResource as Topic from io.kroxylicious.filter.authorization;
                allow User with name = "alice" to * Topic with name = "%s";
                allow User with name = "bob" to CREATE Topic with name = "%s";
                otherwise deny;
                """.formatted(ALICE_TOPIC_NAME, BOB_TOPIC_NAME));
        /*
         * The correctness of this test is predicated on the equivalence of the Proxy ACLs (above) and the Kafka ACLs (below)
         * If you add a rule to one you'll need to add an equivalent rule to the other
         */
        aclBindings = List.of(
                // group permissions
                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, groupId, PatternType.LITERAL),
                        new AccessControlEntry("User:" + ALICE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, groupId, PatternType.LITERAL),
                        new AccessControlEntry("User:" + BOB, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                // Allow Eve to access the group, so we can test the authorization of the topic
                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, groupId, PatternType.LITERAL),
                        new AccessControlEntry("User:" + EVE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),

                // topic permissions
                new AclBinding(
                        new ResourcePattern(ResourceType.TOPIC, ALICE_TOPIC_NAME, PatternType.LITERAL),
                        new AccessControlEntry("User:" + ALICE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                new AclBinding(
                        new ResourcePattern(ResourceType.TOPIC, BOB_TOPIC_NAME, PatternType.LITERAL),
                        new AccessControlEntry("User:" + BOB, "*",
                                AclOperation.CREATE, AclPermissionType.ALLOW)));
    }

    @BeforeEach
    void prepClusters() {
        this.topicIdsInUnproxiedCluster = prepCluster(kafkaClusterWithAuthz, ALL_TOPIC_NAMES_IN_TEST, aclBindings);
        this.topicIdsInProxiedCluster = prepCluster(kafkaClusterNoAuthz, ALL_TOPIC_NAMES_IN_TEST, List.of());
    }

    @AfterEach
    void tidyClusters() {
        deleteTopicsAndAcls(kafkaClusterWithAuthz, ALL_TOPIC_NAMES_IN_TEST, aclBindings);
        deleteTopicsAndAcls(kafkaClusterNoAuthz, ALL_TOPIC_NAMES_IN_TEST, List.of());
    }

    class OffsetCommitEquivalence extends Equivalence<OffsetCommitRequestData, OffsetCommitResponseData> {

        private final RequestTemplate<OffsetCommitRequestData> requestTemplate;

        OffsetCommitEquivalence(
                                short apiVersion,
                                RequestTemplate<OffsetCommitRequestData> requestTemplate) {
            super(apiVersion);
            this.requestTemplate = requestTemplate;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{" +
                    "apiVersion=" + apiVersion() +
                    ", requestTemplate=" + requestTemplate +
                    '}';
        }

        @Override
        public ApiKeys apiKey() {
            return ApiKeys.OFFSET_COMMIT;
        }

        @Override
        public Map<String, String> passwords() {
            return PASSWORDS;
        }

        FindCoordinatorRequestData findCoordinatorRequestData(short findCoordinatorVersion) {
            FindCoordinatorRequestData result = new FindCoordinatorRequestData();

            if (findCoordinatorVersion >= 1) {
                result.setKeyType(FindCoordinatorRequest.CoordinatorType.GROUP.id());
            }
            if (findCoordinatorVersion >= 4) {
                result.coordinatorKeys().add(groupId);
            }
            else {
                result.setKey(groupId);
            }
            return result;
        }

        JoinGroupRequestData joinGroupRequestData(short joinGroupVersion, FindCoordinatorResponseData coordinatorResponse) {
            JoinGroupRequestData result = new JoinGroupRequestData();
            result.setGroupId(groupId);
            result.setMemberId("");
            result.setSessionTimeoutMs(10_000);
            if (joinGroupVersion >= 1) {
                result.setRebalanceTimeoutMs(2_000);
            }
            if (joinGroupVersion >= 5) {
                result.setGroupInstanceId(groupInstanceId);
            }
            if (joinGroupVersion >= 8) {
                result.setReason("Hello, world");
            }
            result.setProtocolType(PROTOCOL_TYPE);
            result.protocols().add(new JoinGroupRequestData.JoinGroupRequestProtocol().setName("proto").setMetadata(new byte[]{1}));
            return result;
        }

        SyncGroupRequestData syncGroupRequestData(short syncGroupVersion, JoinGroupResponseData joinResponse) {
            OffsetCommitAuthzIT.this.memberId = joinResponse.memberId();
            SyncGroupRequestData result = new SyncGroupRequestData();
            result.setGroupId(groupId);
            if (syncGroupVersion >= 3) {
                result.setGroupInstanceId(groupInstanceId);
            }
            result.setMemberId(joinResponse.memberId());
            if (syncGroupVersion >= 5) {
                result.setProtocolType(PROTOCOL_TYPE);
                result.setProtocolName("evwrv");
            }
            result.setGenerationId(++generation);
            result.assignments().add(new SyncGroupRequestData.SyncGroupRequestAssignment()
                    .setMemberId(joinResponse.memberId())
                    .setAssignment(new byte[]{ 42 }));
            return result;
        }

        @Override
        public void prepareCluster(BaseClusterFixture cluster) {
            Map<String, KafkaClient> stringKafkaClientMap = cluster.authenticatedClients(PASSWORDS.keySet());
            stringKafkaClientMap.forEach((username, kafkaClient) -> {
                short findCoordinatorVersion = (short) 1;
                short joinGroupVersion = (short) 1;
                short syncGroupVersion = (short) 1;
                var a = (FindCoordinatorResponseData) kafkaClient.getSync(getRequest(findCoordinatorVersion, this::findCoordinatorRequestData)).payload().message();
                assertThat(Errors.forCode(a.errorCode()))
                        .as("FindCoordinator response: %s", a.errorMessage())
                        .isEqualTo(Errors.NONE);
                var b = (JoinGroupResponseData) kafkaClient.getSync(getRequest(joinGroupVersion, joinGroupRequestData(joinGroupVersion, a))).payload().message();
                assertThat(Errors.forCode(b.errorCode()))
                        .as("JoinGroup response")
                        .isEqualTo(Errors.NONE);
                var c = (SyncGroupResponseData) kafkaClient.getSync(getRequest(syncGroupVersion, syncGroupRequestData(syncGroupVersion, b))).payload().message();
                assertThat(Errors.forCode(c.errorCode()))
                        .as("SyncGroup response")
                        .isEqualTo(Errors.NONE);
            });
        }

        @Override
        public OffsetCommitRequestData requestData(String user, Map<String, Uuid> topicNameToId) {
            return requestTemplate.request(user, topicNameToId);
        }

        @Override
        public ObjectNode convertResponse(OffsetCommitResponseData response) {
            return (ObjectNode) OffsetCommitResponseDataJsonConverter.write(response, apiVersion());
        }

        @Override
        public String clobberResponse(ObjectNode jsonNodes) {
            // var topics = sortArray(jsonNodes, "topics", "name");
            // for (var topics1 : topics) {
            // if (topics1.isObject()) {
            // clobberUuid((ObjectNode) topics1, "topicId");
            // }
            // }
            return prettyJsonString(jsonNodes);
        }

        @Override
        public void assertVisibleSideEffects(BaseClusterFixture cluster) {
            assertThat(offsets(cluster, groupId))
                    .as("Observed offsets in %s", cluster)
                    .isEqualTo(Map.of(
                            new TopicPartition("alice-topic", 0), new OffsetAndMetadata(420)));
        }

        @Override
        public void assertUnproxiedResponses(Map<String, OffsetCommitResponseData> unproxiedResponsesByUser) {
            assertThat(unproxiedResponsesByUser.get(ALICE).topics().stream().filter(t -> t.name().equals(ALICE_TOPIC_NAME)).findFirst()
                    .orElseThrow()
                    .partitions().stream()
                    .allMatch(p -> p.errorCode() == Errors.NONE.code())).isTrue();

            assertThat(unproxiedResponsesByUser.get(EVE).topics().stream().filter(t -> t.name().equals(EVE_TOPIC_NAME)).findFirst()
                    .orElseThrow()
                    .partitions().stream()
                    .allMatch(p -> p.errorCode() == Errors.TOPIC_AUTHORIZATION_FAILED.code())).isTrue();

//            var xx = unproxiedResponsesByUser.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
//                            entry -> prettyJsonString(convertResponse(entry.getValue()))));
//            assertThat(xx).as("UnproxiedResponses").isEqualTo(Map.of("alice", "",
//                    "bob", "",
//                    "eve", ""));
        }
    }

    List<Arguments> test() {
        // The tuples
        List<Short> apiVersions = ApiKeys.OFFSET_COMMIT.allVersions();

        // Compute the n-fold Cartesian product of the tuples (except for pruning)
        List<Arguments> result = new ArrayList<>();
        for (var apiVersion : apiVersions) {
            if (!AuthorizationFilter.isApiVersionSupported(ApiKeys.OFFSET_COMMIT, apiVersion)) {
                UnsupportedApiVersion<ApiMessage, ApiMessage> apiMessageApiMessageUnsupportedApiVersion = new UnsupportedApiVersion<>(ApiKeys.OFFSET_COMMIT, apiVersion);
                result.add(
                        Arguments.of(apiMessageApiMessageUnsupportedApiVersion));
                continue;
            }

            result.add(
                    Arguments.of(new OffsetCommitEquivalence(apiVersion, new RequestTemplate<OffsetCommitRequestData>() {

                        @Override
                        public OffsetCommitRequestData request(String user, Map<String, Uuid> topicIds) {
                            var offsetCommitRequestTopic = new OffsetCommitRequestData.OffsetCommitRequestTopic()
                                    .setName(user + "-topic");
                            offsetCommitRequestTopic
                                    .partitions().add(new OffsetCommitRequestData.OffsetCommitRequestPartition()
                                            .setPartitionIndex(0)
                                            .setCommittedOffset(420)
                                            .setCommittedMetadata("")
                                            .setCommittedLeaderEpoch(1));

                            var data = new OffsetCommitRequestData();
                            data.setGroupId(groupId);
                            if (apiVersion >= 7) {
                                data.setGroupInstanceId(groupId + "-1");
                            }
                            data.setMemberId(memberId);
                            data.setGenerationIdOrMemberEpoch(generation);
                            data.setRetentionTimeMs(123);
                            data.topics().add(offsetCommitRequestTopic);
                            return data;
                        }

                    })));

        }
        return result;
    }

    @ParameterizedTest
    @MethodSource
    void test(VersionSpecificVerification<OffsetCommitRequestData, OffsetCommitResponseData> test) {
        try (var referenceCluster = new ReferenceCluster(kafkaClusterWithAuthz, this.topicIdsInUnproxiedCluster);
                var proxiedCluster = new ProxiedCluster(kafkaClusterNoAuthz, this.topicIdsInProxiedCluster, rulesFile)) {
            test.verifyBehaviour(referenceCluster, proxiedCluster);
        }
    }

    private final String groupId = getClass().getSimpleName();
    private final String groupInstanceId = groupId + "-1";
    private static final String PROTOCOL_TYPE = "consumer";
    private int generation = 0;

}

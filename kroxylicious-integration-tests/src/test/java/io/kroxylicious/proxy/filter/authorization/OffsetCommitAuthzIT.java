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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorRequestDataJsonConverter;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.FindCoordinatorResponseDataJsonConverter;
import org.apache.kafka.common.message.JoinGroupRequestData;
import org.apache.kafka.common.message.JoinGroupRequestDataJsonConverter;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.JoinGroupResponseDataJsonConverter;
import org.apache.kafka.common.message.OffsetCommitRequestData;
import org.apache.kafka.common.message.OffsetCommitResponseData;
import org.apache.kafka.common.message.OffsetCommitResponseDataJsonConverter;
import org.apache.kafka.common.message.SyncGroupRequestData;
import org.apache.kafka.common.message.SyncGroupRequestDataJsonConverter;
import org.apache.kafka.common.message.SyncGroupResponseData;
import org.apache.kafka.common.message.SyncGroupResponseDataJsonConverter;
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

import edu.umd.cs.findbugs.annotations.NonNull;

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

    private static final String FOO_GROUP_ID = "foo";

    private Path rulesFile;

    private List<AclBinding> aclBindings;

    @BeforeAll
    void beforeAll() throws IOException, ExecutionException, InterruptedException {
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
                        new ResourcePattern(ResourceType.GROUP, FOO_GROUP_ID, PatternType.LITERAL),
                        new AccessControlEntry("User:" + ALICE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, FOO_GROUP_ID, PatternType.LITERAL),
                        new AccessControlEntry("User:" + BOB, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                // Allow Eve to access the group, so we can test the authorization of the topic
                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, FOO_GROUP_ID, PatternType.LITERAL),
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

        // ensureInternalTopicsExist(kafkaClusterWithAuthz, "tmpvsdvsv");
        // ensureInternalTopicsExist(kafkaClusterNoAuthz, "tmp");
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

    Map<String, GroupContext> groupContexts = new HashMap<>();

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

        @Override
        public void prepareCluster(BaseClusterFixture cluster) {
            Map<String, KafkaClient> stringKafkaClientMap = cluster.authenticatedClients(PASSWORDS.keySet());
            stringKafkaClientMap.forEach((username, kafkaClient) -> {
                var groupContext = new GroupContext(username, FOO_GROUP_ID);
                groupContext.doUptoSyncedGroup(cluster, kafkaClient);
                groupContexts.put(cluster.name() + username, groupContext);
            });
        }

        @Override
        public OffsetCommitRequestData requestData(String user, BaseClusterFixture clusterFixture) {
            return requestTemplate.request(user, clusterFixture);
        }

        @Override
        public ObjectNode convertResponse(OffsetCommitResponseData response) {
            return (ObjectNode) OffsetCommitResponseDataJsonConverter.write(response, apiVersion());
        }

        @Override
        public String clobberResponse(BaseClusterFixture cluster, ObjectNode jsonNodes) {
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
            assertThat(offsets(cluster, FOO_GROUP_ID))
                    .as("Observed offsets in %s", cluster)
                    .isEqualTo(Map.of(
                            new TopicPartition("alice-topic", 0), 420L));
        }

        @Override
        public Object observedVisibleSideEffects(BaseClusterFixture cluster) {
            return offsets(cluster, FOO_GROUP_ID);
        }

        @Override
        public void assertUnproxiedResponses(Map<String, OffsetCommitResponseData> unproxiedResponsesByUser) {
            assertThat(unproxiedResponsesByUser.get(ALICE).topics().stream()
                    .flatMap(t -> t.partitions().stream().map(p -> {
                        return Map.entry(new TopicPartition(t.name(), p.partitionIndex()), Errors.forCode(p.errorCode()));
                    }))
                    .filter(e -> e.getValue() != Errors.NONE)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .as("%s offsets in %s", ALICE, unproxiedResponsesByUser)
                    .isEmpty();

            assertThat(unproxiedResponsesByUser.get(EVE).topics().stream()
                    .flatMap(t -> t.partitions().stream().map(p -> {
                        return Map.entry(new TopicPartition(t.name(), p.partitionIndex()), Errors.forCode(p.errorCode()));
                    }))
                    .filter(e -> e.getValue() != Errors.TOPIC_AUTHORIZATION_FAILED)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .as("%s offsets in %s", EVE, unproxiedResponsesByUser)
                    .isEmpty();

            // assertThat(unproxiedResponsesByUser.get(EVE).topics().stream().filter(t -> t.name().equals(EVE_TOPIC_NAME)).findFirst()
            // .orElseThrow()
            // .partitions().stream()
            // .allMatch(p -> p.errorCode() == Errors.TOPIC_AUTHORIZATION_FAILED.code())).isTrue();

            // var xx = unproxiedResponsesByUser.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
            // entry -> prettyJsonString(convertResponse(entry.getValue()))));
            // assertThat(xx).as("UnproxiedResponses").isEqualTo(Map.of("alice", "",
            // "bob", "",
            // "eve", ""));
        }
    }

    List<Arguments> shouldEnforceAccessToTopics() {
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
                        public OffsetCommitRequestData request(String user, BaseClusterFixture clusterFixture) {
                            var offsetCommitRequestTopic = new OffsetCommitRequestData.OffsetCommitRequestTopic()
                                    .setName(user + "-topic");
                            offsetCommitRequestTopic
                                    .partitions().add(new OffsetCommitRequestData.OffsetCommitRequestPartition()
                                            .setPartitionIndex(0)
                                            .setCommittedOffset(420)
                                            .setCommittedMetadata("")
                                            .setCommittedLeaderEpoch(1));

                            var data = new OffsetCommitRequestData();
                            data.setGroupId(FOO_GROUP_ID);
                            if (apiVersion >= 7) {
                                data.setGroupInstanceId(FOO_GROUP_ID + "-" + user);
                            }

                            GroupContext groupContext = Objects.requireNonNull(groupContexts.get(clusterFixture.name() + user));
                            data.setMemberId(Objects.requireNonNull(groupContext.memberId));
                            data.setGenerationIdOrMemberEpoch(groupContext.generation);
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
    void shouldEnforceAccessToTopics(VersionSpecificVerification<OffsetCommitRequestData, OffsetCommitResponseData> test) {
        try (var referenceCluster = new ReferenceCluster(kafkaClusterWithAuthz, this.topicIdsInUnproxiedCluster);
                var proxiedCluster = new ProxiedCluster(kafkaClusterNoAuthz, this.topicIdsInProxiedCluster, rulesFile)) {
            test.verifyBehaviour(referenceCluster, proxiedCluster);
        }
    }

    class GroupContext {

        private final String groupId;
        private final String groupInstanceId;
        private static final String PROTOCOL_TYPE = "consumer";
        private final String username;
        private int generation = 0;
        private String memberId;

        GroupContext(String username, String groupId) {
            this.username = username;
            this.groupId = groupId;
            this.groupInstanceId = groupId + "-" + username;
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

        @NonNull
        private FindCoordinatorResponseData doFindCoordinator(BaseClusterFixture cluster, KafkaClient kafkaClient) {
            do {
                short findCoordinatorVersion = (short) 1;
                FindCoordinatorRequestData request = findCoordinatorRequestData(findCoordinatorVersion);
                LOG.info("{} FIND_COORDINATOR{} >> {}",
                        username,
                        prettyJsonString(FindCoordinatorRequestDataJsonConverter.write(request, findCoordinatorVersion)),
                        cluster.name());
                var response = (FindCoordinatorResponseData) kafkaClient.getSync(getRequest(findCoordinatorVersion, request)).payload().message();
                LOG.info("{} FIND_COORDINATOR{} >> {}",
                        username,
                        prettyJsonString(FindCoordinatorResponseDataJsonConverter.write(response, findCoordinatorVersion)),
                        cluster.name());
                Errors actual = Errors.forCode(response.errorCode());
                if (actual == Errors.COORDINATOR_NOT_AVAILABLE) {
                    try {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                assertThat(actual)
                        .as("FindCoordinator response from %s (errorMessage=%s)", cluster, response.errorMessage())
                        .isEqualTo(Errors.NONE);
                return response;
            } while (true);
        }

        @NonNull
        private JoinGroupResponseData doJoinGroup(BaseClusterFixture cluster, KafkaClient kafkaClient, FindCoordinatorResponseData a) {
            short joinGroupVersion = (short) 5;
            JoinGroupRequestData request = joinGroupRequestData(joinGroupVersion, a);
            LOG.info("{} JOIN_GROUP{} >> {}",
                    username,
                    prettyJsonString(JoinGroupRequestDataJsonConverter.write(request, joinGroupVersion)),
                    cluster.name());
            var response = (JoinGroupResponseData) kafkaClient.getSync(getRequest(joinGroupVersion, request)).payload().message();
            LOG.info("{} JOIN_GROUP{} >> {}",
                    username,
                    prettyJsonString(JoinGroupResponseDataJsonConverter.write(response, joinGroupVersion)),
                    cluster.name());
            assertThat(Errors.forCode(response.errorCode()))
                    .as("JoinGroup response from %s", cluster)
                    .isEqualTo(Errors.NONE);

            generation = response.generationId();
            return response;
        }

        private JoinGroupRequestData joinGroupRequestData(short joinGroupVersion, FindCoordinatorResponseData coordinatorResponse) {
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
            result.protocols().add(new JoinGroupRequestData.JoinGroupRequestProtocol().setName("proto").setMetadata(new byte[]{ 1 }));
            return result;
        }

        private void doSyncGroup(BaseClusterFixture cluster, KafkaClient kafkaClient, JoinGroupResponseData b) {
            short syncGroupVersion = (short) 3;
            SyncGroupRequestData request = syncGroupRequestData(syncGroupVersion, b);
            LOG.info("{} SYNC_GROUP{} >> {}",
                    username,
                    prettyJsonString(SyncGroupRequestDataJsonConverter.write(request, syncGroupVersion)),
                    cluster.name());
            var c = (SyncGroupResponseData) kafkaClient.getSync(getRequest(syncGroupVersion, request)).payload().message();
            LOG.info("{} SYNC_GROUP{} >> {}",
                    username,
                    prettyJsonString(SyncGroupResponseDataJsonConverter.write(c, syncGroupVersion)),
                    cluster.name());
            assertThat(Errors.forCode(c.errorCode()))
                    .as("SyncGroup response from %s", cluster)
                    .isEqualTo(Errors.NONE);
        }

        private SyncGroupRequestData syncGroupRequestData(short syncGroupVersion, JoinGroupResponseData joinResponse) {
            this.memberId = Objects.requireNonNull(joinResponse.memberId());
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
            result.setGenerationId(generation);
            result.assignments().add(new SyncGroupRequestData.SyncGroupRequestAssignment()
                    .setMemberId(joinResponse.memberId())
                    .setAssignment(new byte[]{ 42 }));
            return result;
        }

        public void doUptoSyncedGroup(BaseClusterFixture cluster, KafkaClient kafkaClient) {
            var findCoordinatorResponse = doFindCoordinator(cluster, kafkaClient);
            var joinGroupResponse = doJoinGroup(cluster, kafkaClient, findCoordinatorResponse);
            doSyncGroup(cluster, kafkaClient, joinGroupResponse);
        }

    }

}

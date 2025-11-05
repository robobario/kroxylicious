/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.authorization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.RoundRobinAssignor;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.message.AddOffsetsToTxnRequestData;
import org.apache.kafka.common.message.AddOffsetsToTxnResponseData;
import org.apache.kafka.common.message.AddPartitionsToTxnRequestData;
import org.apache.kafka.common.message.AddPartitionsToTxnResponseData;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.InitProducerIdRequestData;
import org.apache.kafka.common.message.InitProducerIdResponseData;
import org.apache.kafka.common.message.JoinGroupRequestData;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.SaslAuthenticateRequestData;
import org.apache.kafka.common.message.SaslAuthenticateResponseData;
import org.apache.kafka.common.message.SaslHandshakeRequestData;
import org.apache.kafka.common.message.SaslHandshakeResponseData;
import org.apache.kafka.common.message.TxnOffsetCommitRequestData;
import org.apache.kafka.common.message.TxnOffsetCommitResponseData;
import org.apache.kafka.common.message.TxnOffsetCommitResponseDataJsonConverter;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.utils.ProducerIdAndEpoch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kroxylicious.filter.authorization.AuthorizationFilter;
import io.kroxylicious.proxy.service.HostPort;
import io.kroxylicious.test.Request;
import io.kroxylicious.test.Response;
import io.kroxylicious.test.client.KafkaClient;
import io.kroxylicious.testing.kafka.api.KafkaCluster;

import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;
import static org.assertj.core.api.Assertions.assertThat;

public class TxnOffsetCommitAuthzIT extends AuthzIT {

    public static final String EXISTING_TOPIC_NAME = "other-topic";
    private Path rulesFile;

    private static final String ALICE_TO_READ_TOPIC_NAME = "alice-new-topic";
    private static final String BOB_TO_READ_TOPIC_NAME = "bob-new-topic";
    public static final List<String> ALL_TOPIC_NAMES_IN_TEST = List.of(EXISTING_TOPIC_NAME, ALICE_TO_READ_TOPIC_NAME, BOB_TO_READ_TOPIC_NAME);
    private static List<AclBinding> aclBindings;
    private TestState adminClientUnproxied;
    private TestState adminClientProxied;

    @BeforeAll
    void beforeAll() throws IOException {
        rulesFile = Files.createTempFile(getClass().getName(), ".aclRules");
        Files.writeString(rulesFile, """
                version 1;
                import User from io.kroxylicious.proxy.authentication;
                import TopicResource as Topic from io.kroxylicious.filter.authorization;
                allow User with name = "alice" to * Topic with name = "%s";
                allow User with name = "bob" to READ Topic with name = "%s";
                otherwise deny;
                """.formatted(ALICE_TO_READ_TOPIC_NAME, BOB_TO_READ_TOPIC_NAME));

        aclBindings = List.of(
                new AclBinding(
                        new ResourcePattern(ResourceType.TOPIC, ALICE_TO_READ_TOPIC_NAME, PatternType.LITERAL),
                        new AccessControlEntry("User:" + ALICE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                new AclBinding(
                        new ResourcePattern(ResourceType.TOPIC, BOB_TO_READ_TOPIC_NAME, PatternType.LITERAL),
                        new AccessControlEntry("User:" + BOB, "*",
                                AclOperation.READ, AclPermissionType.ALLOW)),

                new AclBinding(
                        new ResourcePattern(ResourceType.TRANSACTIONAL_ID, "trans-", PatternType.PREFIXED),
                        new AccessControlEntry("User:" + ALICE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),

                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, "group-", PatternType.PREFIXED),
                        new AccessControlEntry("User:" + ALICE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),
                new AclBinding(
                        new ResourcePattern(ResourceType.TRANSACTIONAL_ID, "trans-", PatternType.PREFIXED),
                        new AccessControlEntry("User:" + EVE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),

                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, "group-", PatternType.PREFIXED),
                        new AccessControlEntry("User:" + EVE, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),

                new AclBinding(
                        new ResourcePattern(ResourceType.TRANSACTIONAL_ID, "trans-", PatternType.PREFIXED),
                        new AccessControlEntry("User:" + BOB, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)),

                new AclBinding(
                        new ResourcePattern(ResourceType.GROUP, "group-", PatternType.PREFIXED),
                        new AccessControlEntry("User:" + BOB, "*",
                                AclOperation.ALL, AclPermissionType.ALLOW)));
    }

    @BeforeEach
    void prepClusters() {
        try {
            this.topicIdsInUnproxiedCluster = prepCluster(kafkaClusterWithAuthz, ALL_TOPIC_NAMES_IN_TEST, aclBindings);
            this.topicIdsInProxiedCluster = prepCluster(kafkaClusterNoAuthz, ALL_TOPIC_NAMES_IN_TEST, List.of());
            adminClientUnproxied = driveTransactionToPointOfTxnOffsetCommit(kafkaClusterWithAuthz, true);
            adminClientProxied = driveTransactionToPointOfTxnOffsetCommit(kafkaClusterNoAuthz, false);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tidyClusters() {
        adminClientUnproxied.superClient.close();
        adminClientProxied.superClient.close();
        adminClientUnproxied = null;
        adminClientProxied = null;
        deleteTopicsAndAcls(kafkaClusterWithAuthz, ALL_TOPIC_NAMES_IN_TEST, aclBindings);
        deleteTopicsAndAcls(kafkaClusterNoAuthz, ALL_TOPIC_NAMES_IN_TEST, List.of());
    }

    record TestState(KafkaClient superClient, String transactionalId, String groupId, ProducerIdAndEpoch producerIdAndEpoch, int generationId, String memberId) {}

    private TestState driveTransactionToPointOfTxnOffsetCommit(KafkaCluster cluster, boolean authenticateAsAdmin) {
        HostPort bootstrapServers = HostPort.parse(cluster.getBootstrapServers());
        KafkaClient adminClientUnproxied = new KafkaClient(bootstrapServers.host(), bootstrapServers.port());
        if (authenticateAsAdmin) {
            saslPlainHandshake(adminClientUnproxied);
            saslPlainAuthenticate(adminClientUnproxied, SUPER, "Super");
        }
        String transactionalId = "trans-" + Uuid.randomUuid();
        String groupId = "group-" + Uuid.randomUuid();
        findCoordinator(FindCoordinatorRequest.CoordinatorType.TRANSACTION, transactionalId, adminClientUnproxied);
        findCoordinator(FindCoordinatorRequest.CoordinatorType.GROUP, groupId, adminClientUnproxied);
        ProducerIdAndEpoch producerIdAndEpoch = initProducerId(adminClientUnproxied, transactionalId);
        MemberIdAndGeneration memberIdAndGeneration = joinGroup(adminClientUnproxied, groupId);
        addPartitionsToTransaction(adminClientUnproxied, transactionalId, producerIdAndEpoch);
        addOffsetsToTxn(adminClientUnproxied, transactionalId, producerIdAndEpoch, groupId);
        return new TestState(adminClientUnproxied, transactionalId, groupId, producerIdAndEpoch, memberIdAndGeneration.generation(), memberIdAndGeneration.memberId());
    }

    private static void addOffsetsToTxn(KafkaClient client, String transactionalId, ProducerIdAndEpoch producerIdAndEpoch, String groupId) {
        AddOffsetsToTxnRequestData request = new AddOffsetsToTxnRequestData();
        request.setTransactionalId(transactionalId);
        request.setProducerId(producerIdAndEpoch.producerId);
        request.setProducerEpoch(producerIdAndEpoch.epoch);
        request.setGroupId(groupId);
        Response response = client.getSync(new Request(ApiKeys.ADD_OFFSETS_TO_TXN, (short) 4, "admin", request));
        assertThat(response.payload().message()).isInstanceOfSatisfying(AddOffsetsToTxnResponseData.class, responseData -> {
            assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
        });
    }

    private static void addPartitionsToTransaction(KafkaClient client, String transactionalId, ProducerIdAndEpoch producerIdAndEpoch) {
        AddPartitionsToTxnRequestData request = new AddPartitionsToTxnRequestData();
        AddPartitionsToTxnRequestData.AddPartitionsToTxnTransaction transaction = new AddPartitionsToTxnRequestData.AddPartitionsToTxnTransaction();
        transaction.setTransactionalId(transactionalId);
        transaction.setProducerId(producerIdAndEpoch.producerId);
        transaction.setProducerEpoch(producerIdAndEpoch.epoch);
        for (String topicName : ALL_TOPIC_NAMES_IN_TEST) {
            AddPartitionsToTxnRequestData.AddPartitionsToTxnTopic topic = new AddPartitionsToTxnRequestData.AddPartitionsToTxnTopic();
            topic.setName(topicName);
            topic.partitions().add(0);
            transaction.topics().add(topic);
        }
        request.transactions().add(transaction);
        Response response = client.getSync(new Request(ApiKeys.ADD_PARTITIONS_TO_TXN, (short) 4, "admin", request));
        assertThat(response.payload().message()).isInstanceOfSatisfying(AddPartitionsToTxnResponseData.class, responseData -> {
            assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
            responseData.resultsByTransaction().forEach(transactionResult -> {
                for (AddPartitionsToTxnResponseData.AddPartitionsToTxnTopicResult topicResult : transactionResult.topicResults()) {
                    topicResult.resultsByPartition().forEach(partition -> {
                        assertThat(Errors.forCode(partition.partitionErrorCode())).isEqualTo(Errors.NONE);
                    });
                }
            });
        });
    }

    record MemberIdAndGeneration(String memberId, int generation) {

    }

    private static MemberIdAndGeneration joinGroup(KafkaClient client, String groupId) {
        AtomicReference<String> memberId = new AtomicReference<>(JoinGroupRequest.UNKNOWN_MEMBER_ID);
        AtomicInteger generationId = new AtomicInteger(JoinGroupRequest.UNKNOWN_GENERATION_ID);
        Awaitility.await().untilAsserted(() -> {
            JoinGroupRequestData request = new JoinGroupRequestData();
            request.setGroupId(groupId);
            request.setMemberId(memberId.get());
            request.setProtocolType("consumer");
            JoinGroupRequestData.JoinGroupRequestProtocol protocol = new JoinGroupRequestData.JoinGroupRequestProtocol();
            protocol.setName(RoundRobinAssignor.ROUNDROBIN_ASSIGNOR_NAME);
            request.protocols().add(protocol);
            request.setSessionTimeoutMs(10000);
            request.setRebalanceTimeoutMs(10000);
            Response response = client.getSync(new Request(ApiKeys.JOIN_GROUP, (short) 9, "admin", request));
            assertThat(response.payload().message()).isInstanceOfSatisfying(JoinGroupResponseData.class, responseData -> {
                if (responseData.memberId() != null && !responseData.memberId().isEmpty()) {
                    memberId.set(responseData.memberId());
                }
                generationId.set(responseData.generationId());
                assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
            });
        });
        return new MemberIdAndGeneration(memberId.get(), generationId.get());
    }

    private static ProducerIdAndEpoch initProducerId(KafkaClient client, String transactionalId) {
        AtomicReference<ProducerIdAndEpoch> producerIdAndEpoch = new AtomicReference<>(ProducerIdAndEpoch.NONE);
        Awaitility.await().untilAsserted(() -> {
            InitProducerIdRequestData request = new InitProducerIdRequestData();
            request.setTransactionalId(transactionalId);
            ProducerIdAndEpoch pep = producerIdAndEpoch.get();
            request.setProducerId(pep.producerId);
            request.setProducerEpoch(pep.epoch);
            request.setTransactionTimeoutMs(10000);
            Response response = client.getSync(new Request(ApiKeys.INIT_PRODUCER_ID, (short) 5, "admin", request));
            assertThat(response.payload().message()).isInstanceOfSatisfying(InitProducerIdResponseData.class, responseData -> {
                assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
                producerIdAndEpoch.set(new ProducerIdAndEpoch(responseData.producerId(), responseData.producerEpoch()));
            });
        });
        return producerIdAndEpoch.get();
    }

    private static void findCoordinator(FindCoordinatorRequest.CoordinatorType transaction, String transactionalId, KafkaClient client) {
        Awaitility.await().untilAsserted(() -> {
            FindCoordinatorRequestData request = new FindCoordinatorRequestData();
            FindCoordinatorRequest.CoordinatorType type = transaction;
            request.setKeyType(type.id());
            request.coordinatorKeys().add(transactionalId);
            Response response = client.getSync(new Request(ApiKeys.FIND_COORDINATOR, (short) 6, "admin", request));
            assertThat(response.payload().message()).isInstanceOfSatisfying(FindCoordinatorResponseData.class, responseData -> {
                assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
                responseData.coordinators().forEach(coordinator -> {
                    assertThat(Errors.forCode(coordinator.errorCode())).isEqualTo(Errors.NONE);
                });
            });
        });
    }

    private static void saslPlainAuthenticate(KafkaClient client, String username, String password) {
        SaslAuthenticateRequestData message = new SaslAuthenticateRequestData();
        message.setAuthBytes((username + "\u0000" + username + "\u0000" + password).getBytes(StandardCharsets.UTF_8));
        Response response = client.getSync(new Request(ApiKeys.SASL_AUTHENTICATE, (short) 2, "admin", message));
        assertThat(response.payload().message()).isInstanceOfSatisfying(SaslAuthenticateResponseData.class, responseData -> {
            assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
        });
    }

    private static void saslPlainHandshake(KafkaClient client) {
        SaslHandshakeRequestData saslHandshakeRequestData = new SaslHandshakeRequestData();
        String mechanism = "PLAIN";
        saslHandshakeRequestData.setMechanism(mechanism);
        Response response = client.getSync(new Request(ApiKeys.SASL_HANDSHAKE, (short) 1, "admin", saslHandshakeRequestData));
        assertThat(response.payload().message()).isInstanceOfSatisfying(SaslHandshakeResponseData.class, responseData -> {
            assertThat(Errors.forCode(responseData.errorCode())).isEqualTo(Errors.NONE);
            assertThat(responseData.mechanisms().contains(mechanism));
        });
    }

    List<Arguments> shouldEnforceAccessToTopics() {
        Stream<Arguments> supportedVersions = IntStream.rangeClosed(AuthorizationFilter.minSupportedApiVersion(ApiKeys.TXN_OFFSET_COMMIT),
                AuthorizationFilter.maxSupportedApiVersion(ApiKeys.TXN_OFFSET_COMMIT))
                .mapToObj(apiVersion -> Arguments.argumentSet("api version " + apiVersion, new TxnOffsetCommitEquivalence((short) apiVersion)));
        Stream<Arguments> unsupportedVersions = IntStream
                .rangeClosed(ApiKeys.OFFSET_FOR_LEADER_EPOCH.oldestVersion(), ApiKeys.OFFSET_FOR_LEADER_EPOCH.latestVersion(true))
                .filter(version -> !AuthorizationFilter.isApiVersionSupported(ApiKeys.OFFSET_FOR_LEADER_EPOCH, (short) version))
                .mapToObj(
                        apiVersion -> Arguments.argumentSet("unsupported version " + apiVersion,
                                new UnsupportedApiVersion<>(ApiKeys.OFFSET_FOR_LEADER_EPOCH, (short) apiVersion)));
        return concat(supportedVersions, unsupportedVersions).toList();
    }

    @ParameterizedTest
    @MethodSource
    void shouldEnforceAccessToTopics(VersionSpecificVerification<TxnOffsetCommitRequestData, TxnOffsetCommitResponseData> test) {
        try (var referenceCluster = new ReferenceCluster(kafkaClusterWithAuthz, this.topicIdsInUnproxiedCluster);
                var proxiedCluster = new ProxiedCluster(kafkaClusterNoAuthz, this.topicIdsInProxiedCluster, rulesFile)) {
            test.verifyBehaviour(referenceCluster, proxiedCluster);
        }
    }

    class TxnOffsetCommitEquivalence extends Equivalence<TxnOffsetCommitRequestData, TxnOffsetCommitResponseData> {

        TxnOffsetCommitEquivalence(short apiVersion) {
            super(apiVersion);
        }

        @Override
        public String clobberResponse(BaseClusterFixture cluster, ObjectNode jsonResponse) {
            return prettyJsonString(jsonResponse);
        }

        @Override
        public void assertVisibleSideEffects(BaseClusterFixture cluster) {

        }

        record TopicPartitionError(String topic, int partition, Errors errors) {}

        @Override
        public void assertUnproxiedResponses(Map<String, TxnOffsetCommitResponseData> unproxiedResponsesByUser) {
            assertThatUserHasTopicPartitions(unproxiedResponsesByUser, BOB, new TopicPartitionError(BOB_TO_READ_TOPIC_NAME, 0, Errors.NONE),
                    new TopicPartitionError(ALICE_TO_READ_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED),
                    new TopicPartitionError(EXISTING_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED));
            assertThatUserHasTopicPartitions(unproxiedResponsesByUser, ALICE, new TopicPartitionError(BOB_TO_READ_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED),
                    new TopicPartitionError(ALICE_TO_READ_TOPIC_NAME, 0, Errors.NONE),
                    new TopicPartitionError(EXISTING_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED));
            assertThatUserHasTopicPartitions(unproxiedResponsesByUser, EVE, new TopicPartitionError(BOB_TO_READ_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED),
                    new TopicPartitionError(ALICE_TO_READ_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED),
                    new TopicPartitionError(EXISTING_TOPIC_NAME, 0, Errors.TOPIC_AUTHORIZATION_FAILED));
        }

        private void assertThatUserHasTopicPartitions(Map<String, TxnOffsetCommitResponseData> unproxiedResponsesByUser, String user,
                                                      TopicPartitionError... topicPartitionErrors) {
            TxnOffsetCommitResponseData txnOffsetCommitResponseData = unproxiedResponsesByUser.get(user);
            assertThat(txnOffsetCommitResponseData).isNotNull();
            Set<TopicPartitionError> collect = getTopicPartitionErrors(txnOffsetCommitResponseData);
            assertThat(collect).containsExactlyInAnyOrder(topicPartitionErrors);
        }

        private static Set<TopicPartitionError> getTopicPartitionErrors(TxnOffsetCommitResponseData response) {
            return response.topics().stream()
                    .flatMap(topic -> topic.partitions().stream()
                            .map(partition -> new TopicPartitionError(topic.name(), partition.partitionIndex(), Errors.forCode(partition.errorCode()))))
                    .collect(toSet());
        }

        @Override
        public ApiKeys apiKey() {
            return ApiKeys.OFFSET_FOR_LEADER_EPOCH;
        }

        @Override
        public Map<String, String> passwords() {
            return PASSWORDS;
        }

        @Override
        public TxnOffsetCommitRequestData requestData(String user, BaseClusterFixture clusterFixture) {
            // ugly, each backing cluster has its own transaction id, group member id etc
            TestState state = clusterFixture.backingCluster() == kafkaClusterNoAuthz ? adminClientProxied : adminClientUnproxied;
            TxnOffsetCommitRequestData txnOffsetCommitRequestData = new TxnOffsetCommitRequestData();
            txnOffsetCommitRequestData.setTransactionalId(state.transactionalId);
            txnOffsetCommitRequestData.setProducerId(state.producerIdAndEpoch.producerId);
            txnOffsetCommitRequestData.setProducerEpoch(state.producerIdAndEpoch.epoch);
            txnOffsetCommitRequestData.setGroupId(state.groupId);
            if (apiVersion() >= 3) {
                txnOffsetCommitRequestData.setGenerationId(state.generationId);
                txnOffsetCommitRequestData.setMemberId(state.memberId);
            }
            TxnOffsetCommitRequestData.TxnOffsetCommitRequestTopic commitTopic = new TxnOffsetCommitRequestData.TxnOffsetCommitRequestTopic();
            commitTopic.setName(EXISTING_TOPIC_NAME);
            TxnOffsetCommitRequestData.TxnOffsetCommitRequestPartition part = new TxnOffsetCommitRequestData.TxnOffsetCommitRequestPartition();
            part.setPartitionIndex(0);
            part.setCommittedOffset(1);
            commitTopic.partitions().add(part);
            txnOffsetCommitRequestData.topics().add(commitTopic);
            TxnOffsetCommitRequestData.TxnOffsetCommitRequestTopic commitTopic2 = new TxnOffsetCommitRequestData.TxnOffsetCommitRequestTopic();
            commitTopic2.setName(ALICE_TO_READ_TOPIC_NAME);
            TxnOffsetCommitRequestData.TxnOffsetCommitRequestPartition part2 = new TxnOffsetCommitRequestData.TxnOffsetCommitRequestPartition();
            part2.setPartitionIndex(0);
            part2.setCommittedOffset(1);
            commitTopic2.partitions().add(part2);
            txnOffsetCommitRequestData.topics().add(commitTopic2);
            TxnOffsetCommitRequestData.TxnOffsetCommitRequestTopic commitTopic3 = new TxnOffsetCommitRequestData.TxnOffsetCommitRequestTopic();
            commitTopic3.setName(BOB_TO_READ_TOPIC_NAME);
            TxnOffsetCommitRequestData.TxnOffsetCommitRequestPartition part3 = new TxnOffsetCommitRequestData.TxnOffsetCommitRequestPartition();
            part3.setPartitionIndex(0);
            part3.setCommittedOffset(1);
            commitTopic3.partitions().add(part3);
            txnOffsetCommitRequestData.topics().add(commitTopic3);
            return txnOffsetCommitRequestData;
        }

        @Override
        public ObjectNode convertResponse(TxnOffsetCommitResponseData response) {
            return (ObjectNode) TxnOffsetCommitResponseDataJsonConverter.write(response, apiVersion());
        }
    }

}

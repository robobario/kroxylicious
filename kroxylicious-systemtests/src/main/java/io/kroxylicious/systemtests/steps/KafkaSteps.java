/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.steps;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.ConfigMap;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;

import org.apache.kafka.clients.admin.ScramMechanism;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.batch.v1.Job;

import io.kroxylicious.systemtests.Constants;
import io.kroxylicious.systemtests.templates.testclients.TestClientsJobTemplates;
import io.kroxylicious.systemtests.utils.DeploymentUtils;
import io.kroxylicious.systemtests.utils.KafkaUtils;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The type Kafka steps.
 */
public class KafkaSteps {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaSteps.class);
    private static final String TOPIC_COMMAND = "topic";
    private static final String BOOTSTRAP_ARG = "--bootstrap-server=";

    private KafkaSteps() {
    }

    /**
     * Create topic.
     *
     * @param deployNamespace the deploy namespace
     * @param topicName the topic name
     * @param bootstrap the bootstrap
     * @param partitions the partitions
     * @param replicas the replicas
     */
    public static void createTopic(String deployNamespace, String topicName, String bootstrap, int partitions, int replicas) {
        createTopic(deployNamespace, topicName, bootstrap, partitions, replicas, CompressionType.NONE);
    }

    /**
     * Create topic.
     *
     * @param deployNamespace the deploy namespace
     * @param topicName the topic name
     * @param bootstrap the bootstrap
     * @param partitions the partitions
     * @param replicas the replicas
     * @param compressionType the compression type
     */
    public static void createTopic(String deployNamespace, String topicName, String bootstrap, int partitions, int replicas,
                                   @NonNull CompressionType compressionType) {
        createTopic(deployNamespace, topicName, bootstrap, partitions, replicas, compressionType, null);
    }

    /**
     * Create topic.
     *
     * @param deployNamespace the deploy namespace
     * @param topicName the topic name
     * @param bootstrap the bootstrap
     * @param partitions the partitions
     * @param replicas the replicas
     * @param usernamePasswords the username passwords
     */
    public static void createTopic(String deployNamespace, String topicName, String bootstrap, int partitions, int replicas,
                                   Map<String, String> usernamePasswords) {
        createTopic(deployNamespace, topicName, bootstrap, partitions, replicas, CompressionType.NONE, usernamePasswords);
    }

    /**
     * Create topic.
     *
     * @param deployNamespace the deploy namespace
     * @param topicName the topic name
     * @param bootstrap the bootstrap
     * @param partitions the partitions
     * @param replicas the replicas
     * @param compressionType the compression type
     */
    public static void createTopic(String deployNamespace, String topicName, String bootstrap, int partitions, int replicas,
                                   @NonNull CompressionType compressionType, @Nullable Map<String, String> usernamePasswords) {
        LOGGER.atDebug().setMessage("Creating '{}' topic").addArgument(topicName).log();
        String name = Constants.KAFKA_ADMIN_CLIENT_LABEL + "-create";
        List<String> args = new ArrayList<>(
                List.of(TOPIC_COMMAND, "create", BOOTSTRAP_ARG + bootstrap, "--topic=" + topicName, "--topic-partitions=" + partitions,
                        "--topic-rep-factor=" + replicas));

        List<String> topicConfig = new ArrayList<>();
        if (!CompressionType.NONE.equals(compressionType)) {
            topicConfig.add(TopicConfig.COMPRESSION_TYPE_CONFIG + "=" + compressionType);
        }

        Properties adminConfig = new Properties();
        if (usernamePasswords != null && !usernamePasswords.isEmpty()) {
            if (!usernamePasswords.containsKey(Constants.KROXYLICIOUS_ADMIN_USER)) {
                throw new ConfigException("'admin' user not found! It is necessary to manage the topics");
            }
            adminConfig.put("ADDITIONAL_CONFIG", "security.protocol=" + SecurityProtocol.SASL_PLAINTEXT.name
                    + "\n" + "sasl.mechanism=" + ScramMechanism.SCRAM_SHA_512.mechanismName()
                    + "\n" + "sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" + Constants.KROXYLICIOUS_ADMIN_USER
                    + "\" password=\"" + usernamePasswords.get(Constants.KROXYLICIOUS_ADMIN_USER) + "\";");
        }

        if (!topicConfig.isEmpty()) {
            args.add("--topic-config=" + String.join(",", topicConfig));
        }


        List<VolumeMount> volumeMounts = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        if (!adminConfig.isEmpty()){
            String properties;
            try (StringWriter writer = new StringWriter()) {
                adminConfig.store(writer, "admin config");
                properties = writer.toString();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ConfigMap map = new ConfigMapBuilder().withNewMetadata().withName("admin-client-config").endMetadata()
                    .withData(Map.of("config.properties", properties)).build();
            map = kubeClient().getClient().configMaps().inNamespace(deployNamespace).resource(map).createOr(NonDeletingOperation::update);

            Volume configVolume = new VolumeBuilder()
                    .withName("admin-client-config")
                    .withConfigMap(new ConfigMapVolumeSourceBuilder()
                            .withName("admin-client-config")
                            .addNewItem()
                            .withKey("config.properties")
                            .withPath("config.properties")
                            .endItem()
                            .build())
                    .build();

            VolumeMount configMount = new VolumeMountBuilder()
                    .withName("admin-client-config")
                    .withMountPath("/home/strimzi/.admin_client/")
                    .build();
            volumeMounts.add(configMount);
            volumes.add(configVolume);
        }

        Job adminClientJob = TestClientsJobTemplates.defaultAdminClientJob(name, args, volumes, volumeMounts).build();
        kubeClient().getClient().batch().v1().jobs().inNamespace(deployNamespace).resource(adminClientJob).create();
        String podName = KafkaUtils.getPodNameByLabel(deployNamespace, "app", name, Duration.ofSeconds(30));
        DeploymentUtils.waitForPodRunSucceeded(deployNamespace, podName, Duration.ofMinutes(5));
        LOGGER.atDebug().setMessage("Admin client create pod log: {}").addArgument(kubeClient().logsInSpecificNamespace(deployNamespace, podName)).log();
    }

    /**
     * Delete topic.
     *
     * @param deployNamespace the deploy namespace
     * @param topicName the topic name
     * @param bootstrap the bootstrap
     */
    public static void deleteTopic(String deployNamespace, String topicName, String bootstrap) {
        LOGGER.atDebug().setMessage("Deleting '{}' topic").addArgument(topicName).log();
        String name = Constants.KAFKA_ADMIN_CLIENT_LABEL + "-delete";
        List<String> args = List.of(TOPIC_COMMAND, "delete", BOOTSTRAP_ARG + bootstrap, "--if-exists", "--topic=" + topicName);

        Job adminClientJob = TestClientsJobTemplates.defaultAdminClientJob(name, args,List.of(), List.of()).build();
        kubeClient().getClient().batch().v1().jobs().inNamespace(deployNamespace).resource(adminClientJob).create();

        String podName = KafkaUtils.getPodNameByLabel(deployNamespace, "app", name, Duration.ofSeconds(30));
        DeploymentUtils.waitForPodRunSucceeded(deployNamespace, podName, Duration.ofMinutes(1));
        LOGGER.atDebug().setMessage("Admin client delete pod log: {}").addArgument(kubeClient().logsInSpecificNamespace(deployNamespace, podName)).log();
    }

    /**
     * Restart kafka broker.
     *
     * @param clusterName the cluster name
     */
    public static void restartKafkaBroker(String clusterName) {
        clusterName = clusterName + "-kafka";
        assertThat("Broker has not been restarted successfully!", KafkaUtils.restartBroker(Constants.KAFKA_DEFAULT_NAMESPACE, clusterName));
    }
}

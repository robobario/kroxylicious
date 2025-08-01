/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.OperatorException;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.ManagedWorkflowAndDependentResourceContext;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.PrimaryToSecondaryMapper;
import io.javaoperatorsdk.operator.processing.event.source.SecondaryToPrimaryMapper;

import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.common.FilterRef;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolFilter;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolFilterBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyIngress;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyIngressBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyStatus;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaService;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaServiceBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaCluster;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaClusterBuilder;
import io.kroxylicious.kubernetes.operator.assertj.AssertFactory;
import io.kroxylicious.kubernetes.operator.assertj.OperatorAssertions;

import edu.umd.cs.findbugs.annotations.NonNull;

import static io.kroxylicious.kubernetes.operator.ResourcesUtil.name;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaProxyReconcilerTest {

    public static final Clock TEST_CLOCK = Clock.fixed(Instant.EPOCH, ZoneId.of("Z"));

    @Mock(strictness = Mock.Strictness.LENIENT)
    Context<KafkaProxy> reconcilerContext;

    @Mock
    ManagedWorkflowAndDependentResourceContext workdflowContext;

    private AutoCloseable closeable;

    @BeforeEach
    void openMocks() {
        closeable = MockitoAnnotations.openMocks(this);
        when(reconcilerContext.managedWorkflowAndDependentResourceContext()).thenReturn(workdflowContext);
    }

    @AfterEach
    void releaseMocks() throws Exception {
        closeable.close();
    }

    @Test
    void successfulInitialReconciliationShouldResultInReadyTrueCondition() {
        // Given
        // @formatter:off
        long generation = 42L;
        var primary = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withUid(UUID.randomUUID().toString())
                    .withName("my-proxy")
                .endMetadata()
                .build();
        // @formatter:on

        // When
        var updateControl = newKafkaProxyReconciler(TEST_CLOCK).reconcile(primary, reconcilerContext);

        // Then
        assertThat(updateControl.isPatchStatus()).isTrue();
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());
        statusAssert.hasObservedGeneration(generation)
                .conditionList()
                .containsOnlyTypes(Condition.Type.Ready)
                .singleOfType(Condition.Type.Ready)
                .isReadyTrue();
        assertThat(updateControl.isPatchResource()).isFalse();
        assertThat(updateControl.getResource().get())
                .satisfies(kp -> OperatorAssertions.assertThat(kp).doesNotHaveAnnotation(Annotations.REFERENT_CHECKSUM_ANNOTATION_KEY));
    }

    @Test
    void failedInitialReconciliationShouldResultInReadyTrueCondition() {
        // Given
        // @formatter:off
        long generation = 42L;
        var proxy = new KafkaProxyBuilder()
                .withNewMetadata()
                .withGeneration(generation)
                .withName("my-proxy")
                .endMetadata()
                .build();
        // @formatter:on

        // When
        var updateControl = newKafkaProxyReconciler(TEST_CLOCK)
                .updateErrorStatus(proxy, reconcilerContext, new InvalidResourceException("Resource was terrible"));

        // Then
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());
        statusAssert.hasObservedGeneration(generation)
                .singleCondition()
                .hasObservedGeneration(generation)
                .hasLastTransitionTime(TEST_CLOCK.instant())
                .hasType(Condition.Type.Ready)
                .hasStatus(Condition.Status.UNKNOWN)
                .hasReason(InvalidResourceException.class.getName())
                .hasMessage("Resource was terrible");

    }

    @Test
    void remainInReadyTrueShouldRetainTransitionTime() {
        // Given
        long generation = 42L;
        // @formatter:off
        var primary = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withUid(UUID.randomUUID().toString())
                    .withName("my-proxy")
                .endMetadata()
                .withNewStatus()
                    .addNewCondition()
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.TRUE)
                        .withObservedGeneration(generation)
                        .withMessage("")
                        .withReason("")
                        .withLastTransitionTime(TEST_CLOCK.instant())
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on

        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime).reconcile(primary, reconcilerContext);

        // Then
        assertThat(updateControl.isPatchStatus()).isTrue();
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());
        statusAssert.hasObservedGeneration(generation)
                .conditionList()
                .containsOnlyTypes(Condition.Type.Ready)
                .singleOfType(Condition.Type.Ready)
                .isReadyTrue();
    }

    @Test
    void transitionToReadyUnknownShouldChangeTransitionTime() {
        // Given
        long generation = 42L;
        // @formatter:off
        var primary = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withName("my-proxy")
                .endMetadata()
                .withNewStatus()
                    .addNewCondition()
                        .withObservedGeneration(generation)
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.TRUE)
                        .withObservedGeneration(generation)
                        .withMessage("")
                        .withReason("")
                        .withLastTransitionTime(TEST_CLOCK.instant())
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on
        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime)
                .updateErrorStatus(primary, reconcilerContext, new InvalidResourceException("Resource was terrible"));

        // Then
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());
        statusAssert.hasObservedGeneration(generation)
                .singleCondition()
                .hasObservedGeneration(generation)
                .hasLastTransitionTime(reconciliationTime.instant())
                .hasType(Condition.Type.Ready)
                .hasStatus(Condition.Status.UNKNOWN)
                .hasReason(InvalidResourceException.class.getName())
                .hasMessage("Resource was terrible");
    }

    @Test
    void remainInReadyUnknownShouldRetainTransitionTime() {
        // Given
        long generation = 42L;
        // @formatter:off
        Instant originalInstant = TEST_CLOCK.instant();
        var primary = new KafkaProxyBuilder()
                 .withNewMetadata()
                    .withGeneration(generation)
                    .withName("my-proxy")
                .endMetadata()
                .withNewStatus()
                    .addNewCondition()
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.UNKNOWN)
                        .withObservedGeneration(generation)
                        .withMessage("Resource was terrible")
                        .withReason(InvalidResourceException.class.getSimpleName())
                        .withLastTransitionTime(originalInstant)
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on
        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime)
                .updateErrorStatus(primary, reconcilerContext, new InvalidResourceException("Resource was terrible"));

        // Then
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());

        statusAssert.hasObservedGeneration(generation)
                .singleCondition()
                .hasObservedGeneration(generation)
                .hasLastTransitionTime(originalInstant)
                .hasType(Condition.Type.Ready)
                .hasStatus(Condition.Status.UNKNOWN)
                .hasReason(InvalidResourceException.class.getName())
                .hasMessage("Resource was terrible");
    }

    @Test
    void staleReferentStatusExceptionResultsInNoStatusUpdate() {
        // Given
        long generation = 42L;
        // @formatter:off
        Instant originalInstant = TEST_CLOCK.instant();
        var primary = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withName("my-proxy")
                .endMetadata()
                .withNewStatus()
                    .addNewCondition()
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.UNKNOWN)
                        .withObservedGeneration(generation)
                        .withMessage("Resource was terrible")
                        .withReason(InvalidResourceException.class.getSimpleName())
                        .withLastTransitionTime(originalInstant)
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on
        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime)
                .updateErrorStatus(primary, reconcilerContext, new StaleReferentStatusException("stale virtualcluster status"));

        // Then
        assertThat(updateControl.getResource()).isEmpty();
    }

    @Test
    void staleReferentStatusExceptionCausingOperatorExceptionResultsInNoStatusUpdate() {
        // Given
        long generation = 42L;
        // @formatter:off
        Instant originalInstant = TEST_CLOCK.instant();
        var primary = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withName("my-proxy")
                .endMetadata()
                .withNewStatus()
                    .addNewCondition()
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.UNKNOWN)
                        .withObservedGeneration(generation)
                        .withMessage("Resource was terrible")
                        .withReason(InvalidResourceException.class.getSimpleName())
                        .withLastTransitionTime(originalInstant)
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on
        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime)
                .updateErrorStatus(primary, reconcilerContext, new OperatorException(new StaleReferentStatusException("stale virtualcluster status")));

        // Then
        assertThat(updateControl.getResource()).isEmpty();
    }

    @Test
    void transitionToReadyTrueShouldChangeTransitionTime() {
        // Given
        long generation = 42L;
        // @formatter:off
        var proxy = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withUid(UUID.randomUUID().toString())
                    .withName("my-proxy")
                .endMetadata()
                .withNewStatus()
                    .addNewCondition()
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.FALSE)
                        .withMessage("Resource was terrible")
                        .withReason(InvalidResourceException.class.getSimpleName())
                        .withObservedGeneration(generation)
                        .withLastTransitionTime(TEST_CLOCK.instant())
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on
        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime).reconcile(proxy, reconcilerContext);

        // Then
        assertThat(updateControl.isPatchStatus()).isTrue();
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());
        statusAssert.hasObservedGeneration(generation)
                .conditionList()
                .containsOnlyTypes(Condition.Type.Ready)
                .singleOfType(Condition.Type.Ready)
                .isReadyTrue();
    }

    @Test
    void transitionToReadyFalseShouldChangeTransitionTime2() {
        // Given
        long generation = 42L;
        // @formatter:off
        var primary = new KafkaProxyBuilder()
                .withNewMetadata()
                    .withGeneration(generation)
                    .withUid(UUID.randomUUID().toString())
                    .withName("my-proxy")
                    .withNamespace("my-ns")
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .withNewStatus()
                    .addNewCondition()
                        .withObservedGeneration(generation)
                        .withType(Condition.Type.Ready)
                        .withStatus(Condition.Status.TRUE)
                        .withObservedGeneration(generation)
                        .withMessage("")
                        .withReason("")
                        .withLastTransitionTime(TEST_CLOCK.instant())
                    .endCondition()
                .endStatus()
                .build();
        // @formatter:on
        Clock reconciliationTime = Clock.offset(TEST_CLOCK, Duration.ofSeconds(1));
        doReturn(workdflowContext).when(reconcilerContext).managedWorkflowAndDependentResourceContext();
        doReturn(Set.of(new VirtualKafkaClusterBuilder().withNewMetadata().withName("my-cluster").withNamespace("my-ns").endMetadata().withNewSpec().withNewProxyRef()
                .withName("my-proxy").endProxyRef().endSpec().build())).when(reconcilerContext).getSecondaryResources(VirtualKafkaCluster.class);

        // When
        var updateControl = newKafkaProxyReconciler(reconciliationTime).reconcile(primary, reconcilerContext);

        // Then
        assertThat(updateControl.isPatchStatus()).isTrue();
        var statusAssert = assertThat(updateControl.getResource())
                .isNotEmpty().get()
                .extracting(KafkaProxy::getStatus, AssertFactory.status());
        statusAssert.hasObservedGeneration(generation)
                .conditionList()
                .containsOnlyTypes(Condition.Type.Ready)
                .singleOfType(Condition.Type.Ready)
                .isReadyTrue();
    }

    @NonNull
    private static KafkaProxyReconciler newKafkaProxyReconciler(Clock reconciliationTime) {
        return new KafkaProxyReconciler(reconciliationTime, SecureConfigInterpolator.DEFAULT_INTERPOLATOR);
    }

    @Test
    void proxyToClusterMapper() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<VirtualKafkaCluster, KubernetesResourceList<VirtualKafkaCluster>, Resource<VirtualKafkaCluster>> mockOperation = mock();
        when(client.resources(VirtualKafkaCluster.class)).thenReturn(mockOperation);
        KubernetesResourceList<VirtualKafkaCluster> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = buildProxy("proxy");
        VirtualKafkaCluster cluster = baseVirtualKafkaClusterBuilder(proxy, "cluster", 1L, 1L).build();
        VirtualKafkaCluster clusterForAnotherProxy = baseVirtualKafkaClusterBuilder(buildProxy("anotherproxy"), "anothercluster", 1L, 1L).build();
        when(mockList.getItems()).thenReturn(List.of(cluster, clusterForAnotherProxy));
        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToClusterMapper(eventSourceContext);
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).containsExactly(ResourceID.fromResource(cluster));
    }

    @Test
    void proxyToIngressMapper() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxyIngress, KubernetesResourceList<KafkaProxyIngress>, Resource<KafkaProxyIngress>> mockOperation = mock();
        when(client.resources(KafkaProxyIngress.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxyIngress> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxyIngress ingress = new KafkaProxyIngressBuilder().withNewMetadata().withName("ingress").endMetadata().withNewSpec().withNewProxyRef()
                .withName("proxy")
                .endProxyRef().endSpec().build();
        KafkaProxyIngress ingressForAnotherProxy = new KafkaProxyIngressBuilder().withNewMetadata().withName("ingress2").endMetadata().withNewSpec().withNewProxyRef()
                .withName("anotherProxy")
                .endProxyRef().endSpec().build();
        when(mockList.getItems()).thenReturn(List.of(ingress, ingressForAnotherProxy));
        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToIngressMapper(eventSourceContext);
        KafkaProxy proxy = new KafkaProxyBuilder().withNewMetadata().withName("proxy").endMetadata().build();
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).containsExactly(ResourceID.fromResource(ingress));
    }

    @Test
    void proxyToClusterMapper_NoMatchedClusters() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<VirtualKafkaCluster, KubernetesResourceList<VirtualKafkaCluster>, Resource<VirtualKafkaCluster>> mockOperation = mock();
        when(client.resources(VirtualKafkaCluster.class)).thenReturn(mockOperation);
        KubernetesResourceList<VirtualKafkaCluster> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        VirtualKafkaCluster clusterForAnotherProxy = baseVirtualKafkaClusterBuilder(buildProxy("anotherProxy"), "cluster", 1L, 1L).build();
        when(mockList.getItems()).thenReturn(List.of(clusterForAnotherProxy));
        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToClusterMapper(eventSourceContext);
        KafkaProxy proxy = buildProxy("proxy");
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).isEmpty();
    }

    @Test
    void clusterToProxyMapper() {
        // given
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = buildProxy("proxy");
        when(mockList.getItems()).thenReturn(List.of(proxy));
        SecondaryToPrimaryMapper<VirtualKafkaCluster> mapper = KafkaProxyReconciler.clusterToProxyMapper(eventSourceContext);

        // when
        VirtualKafkaCluster cluster = baseVirtualKafkaClusterBuilder(proxy, "cluster", 1L, 1L).build();
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(cluster);

        // then
        assertThat(primaryResourceIDs).containsExactly(ResourceID.fromResource(proxy));
    }

    @Test
    void clusterToProxyMapperIgnoresClusterWithStaleStatus() {
        // given
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = buildProxy("proxy");
        when(mockList.getItems()).thenReturn(List.of(proxy));
        SecondaryToPrimaryMapper<VirtualKafkaCluster> mapper = KafkaProxyReconciler.clusterToProxyMapper(eventSourceContext);

        // when
        VirtualKafkaCluster cluster = baseVirtualKafkaClusterBuilder(proxy, "cluster", 2L, 1L).build();
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(cluster);

        // then
        assertThat(primaryResourceIDs).isEmpty();
    }

    @Test
    void ingressToProxyMapper() {
        // given
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = new KafkaProxyBuilder().withNewMetadata().withName("proxy").endMetadata().build();
        when(mockList.getItems()).thenReturn(List.of(proxy));
        SecondaryToPrimaryMapper<KafkaProxyIngress> mapper = KafkaProxyReconciler.ingressToProxyMapper(eventSourceContext);
        KafkaProxyIngress cluster = new KafkaProxyIngressBuilder().withNewMetadata().withName("ingress").endMetadata().withNewSpec().withNewProxyRef()
                .withName("proxy")
                .endProxyRef().endSpec().build();

        // when
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(cluster);

        // then
        assertThat(primaryResourceIDs).containsExactly(ResourceID.fromResource(proxy));
    }

    @Test
    void ingressToProxyMapperIgnoresIngressWithStaleStatus() {
        // given
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = new KafkaProxyBuilder().withNewMetadata().withName("proxy").endMetadata().build();
        when(mockList.getItems()).thenReturn(List.of(proxy));
        SecondaryToPrimaryMapper<KafkaProxyIngress> mapper = KafkaProxyReconciler.ingressToProxyMapper(eventSourceContext);
        // @formatter:off
        KafkaProxyIngress ingress = new KafkaProxyIngressBuilder()
                .withNewMetadata()
                    .withName("ingress")
                    .withGeneration(23L)
                .endMetadata()
                .withNewSpec()
                    .withNewProxyRef()
                        .withName("proxy")
                    .endProxyRef()
                .endSpec()
                .withNewStatus()
                    .withObservedGeneration(20L)
                .endStatus()
                .build();
        // @formatter:on

        // when
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(ingress);

        // then
        assertThat(primaryResourceIDs).isEmpty();
    }

    @Test
    void filterToProxyMapper() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = buildProxy("proxy");
        when(mockList.getItems()).thenReturn(List.of(proxy));
        SecondaryToPrimaryMapper<KafkaProtocolFilter> mapper = KafkaProxyReconciler.filterToProxy(eventSourceContext);
        String namespace = "test";
        KafkaProtocolFilter filter = new KafkaProtocolFilterBuilder().withNewMetadata().withName("filter").withNamespace(namespace).endMetadata().build();
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(filter);
        assertThat(primaryResourceIDs).containsExactly(ResourceID.fromResource(proxy));
        verify(mockOperation).inNamespace(namespace);
    }

    @Test
    void filterToProxyMapperIgnoresFilterWithStaleStatus() {
        // given
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);
        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        KafkaProxy proxy = buildProxy("proxy");
        when(mockList.getItems()).thenReturn(List.of(proxy));
        SecondaryToPrimaryMapper<KafkaProtocolFilter> mapper = KafkaProxyReconciler.filterToProxy(eventSourceContext);
        String namespace = "test";
        // @formatter:off
        KafkaProtocolFilter filter = new KafkaProtocolFilterBuilder()
                .withNewMetadata()
                    .withGeneration(4L)
                    .withName("filter")
                    .withNamespace(namespace)
                .endMetadata()
                .withNewStatus()
                    .withObservedGeneration(1L)
                .endStatus()
                .build();
        // @formatter:on
        // when
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(filter);

        // then
        assertThat(primaryResourceIDs).isEmpty();
    }

    @Test
    void proxyToFilterMapping() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        MixedOperation<VirtualKafkaCluster, KubernetesResourceList<VirtualKafkaCluster>, Resource<VirtualKafkaCluster>> mockOperation = mock();
        when(client.resources(VirtualKafkaCluster.class)).thenReturn(mockOperation);
        KubernetesResourceList<VirtualKafkaCluster> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        String proxyName = "proxy";
        String proxyNamespace = "test";
        String filterName = "filter";
        KafkaProxy proxy = new KafkaProxyBuilder().withNewMetadata().withName(proxyName).withNamespace(proxyNamespace).endMetadata().build();
        VirtualKafkaCluster clusterForAnotherProxy = baseVirtualKafkaClusterBuilder(proxy, "cluster", 1L, 1L).editOrNewSpec()
                .addNewFilterRef().withName(filterName).endFilterRef().endSpec().build();
        when(mockList.getItems()).thenReturn(List.of(clusterForAnotherProxy));
        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToFilters(eventSourceContext);
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).containsExactly(new ResourceID(filterName, proxyNamespace));
        verify(mockOperation).inNamespace(proxyNamespace);
    }

    @Test
    void proxyToFilterMappingForClusterWithoutFilters() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);
        KubernetesResourceList<VirtualKafkaCluster> mockList = mockVirtualKafkaClusterListOperation(client);
        KafkaProxy proxy = buildProxy("proxy");
        VirtualKafkaCluster clusterWithoutFilters = baseVirtualKafkaClusterBuilder(proxy, "cluster", 1L, 1L)
                .editOrNewSpec()
                .withFilterRefs((List<FilterRef>) null)
                .endSpec()
                .build();
        when(mockList.getItems()).thenReturn(List.of(clusterWithoutFilters));

        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToFilters(eventSourceContext);
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).isEmpty();
    }

    @Test
    void proxyToKafkaServiceMapper() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaProxy proxy = buildProxy("proxy");

        KubernetesResourceList<VirtualKafkaCluster> clusterListMock = mockVirtualKafkaClusterListOperation(client);

        KubernetesResourceList<KafkaService> clusterRefListMock = mockKafkaServiceListOperation(client);

        KafkaService kafkaServiceRef = buildKafkaService("ref", 1L, 1L);

        when(clusterRefListMock.getItems()).thenReturn(List.of(kafkaServiceRef));

        VirtualKafkaCluster cluster = buildVirtualKafkaCluster(proxy, "cluster", kafkaServiceRef);

        when(clusterListMock.getItems()).thenReturn(List.of(cluster));

        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToKafkaServiceMapper(eventSourceContext);
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).containsExactly(ResourceID.fromResource(kafkaServiceRef));
    }

    @Test
    void proxyToKafkaServiceMapperDistinguishesByProxy() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaProxy proxy1 = buildProxy("proxy1");
        KafkaProxy proxy2 = buildProxy("proxy2");

        KubernetesResourceList<VirtualKafkaCluster> clusterListMock = mockVirtualKafkaClusterListOperation(client);

        KubernetesResourceList<KafkaService> clusterRefListMock = mockKafkaServiceListOperation(client);

        KafkaService kafkaServiceRefProxy1 = buildKafkaService("proxy1ref", 1L, 1L);
        KafkaService kafkaServiceRefProxy2 = buildKafkaService("proxy2ref", 1L, 1L);

        when(clusterRefListMock.getItems()).thenReturn(List.of(kafkaServiceRefProxy1, kafkaServiceRefProxy2));

        VirtualKafkaCluster clusterProxy1 = buildVirtualKafkaCluster(proxy1, "proxy1cluster", kafkaServiceRefProxy1);
        VirtualKafkaCluster clusterProxy2 = buildVirtualKafkaCluster(proxy2, "proxy2cluster", kafkaServiceRefProxy2);

        when(clusterListMock.getItems()).thenReturn(List.of(clusterProxy1, clusterProxy2));

        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToKafkaServiceMapper(eventSourceContext);

        assertThat(mapper.toSecondaryResourceIDs(proxy1)).containsExactly(ResourceID.fromResource(kafkaServiceRefProxy1));
        assertThat(mapper.toSecondaryResourceIDs(proxy2)).containsExactly(ResourceID.fromResource(kafkaServiceRefProxy2));
    }

    @Test
    void proxyToKafkaServiceMapperHandlesProxyWithMultipleRefs() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaProxy proxy1 = buildProxy("proxy1");
        KafkaProxy proxy2 = buildProxy("proxy2");

        KubernetesResourceList<VirtualKafkaCluster> clusterListMock = mockVirtualKafkaClusterListOperation(client);

        KubernetesResourceList<KafkaService> clusterRefListMock = mockKafkaServiceListOperation(client);

        KafkaService kafkaServiceRefProxy1 = buildKafkaService("proxy1ref", 1L, 1L);

        KafkaService kafkaServiceRefProxy2a = buildKafkaService("proxy2refa", 1L, 1L);
        KafkaService kafkaServiceRefProxy2b = buildKafkaService("proxy2refb", 1L, 1L);

        when(clusterRefListMock.getItems()).thenReturn(List.of(kafkaServiceRefProxy1, kafkaServiceRefProxy2a, kafkaServiceRefProxy2b));

        VirtualKafkaCluster clusterProxy1 = buildVirtualKafkaCluster(proxy1, "proxy1cluster", kafkaServiceRefProxy1);
        VirtualKafkaCluster clusterProxy2a = buildVirtualKafkaCluster(proxy2, "proxy2clustera", kafkaServiceRefProxy2a);
        VirtualKafkaCluster clusterProxy2b = buildVirtualKafkaCluster(proxy2, "proxy2clusterb", kafkaServiceRefProxy2b);

        when(clusterListMock.getItems()).thenReturn(List.of(clusterProxy1, clusterProxy2a, clusterProxy2b));

        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToKafkaServiceMapper(eventSourceContext);

        assertThat(mapper.toSecondaryResourceIDs(proxy1))
                .containsExactly(ResourceID.fromResource(kafkaServiceRefProxy1));

        assertThat(mapper.toSecondaryResourceIDs(proxy2))
                .containsExactly(ResourceID.fromResource(kafkaServiceRefProxy2a), ResourceID.fromResource(kafkaServiceRefProxy2b));
    }

    @Test
    void proxyToKafkaServiceMapperIgnoresDanglingKafkaService() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaProxy proxy = buildProxy("proxy");

        KubernetesResourceList<VirtualKafkaCluster> clusterListMock = mockVirtualKafkaClusterListOperation(client);
        mockKafkaServiceListOperation(client);

        VirtualKafkaCluster cluster = buildVirtualKafkaCluster(proxy, "cluster", buildKafkaService("dangle", 1L, 1L));

        when(clusterListMock.getItems()).thenReturn(List.of(cluster));

        PrimaryToSecondaryMapper<KafkaProxy> mapper = KafkaProxyReconciler.proxyToKafkaServiceMapper(eventSourceContext);
        Set<ResourceID> secondaryResourceIDs = mapper.toSecondaryResourceIDs(proxy);
        assertThat(secondaryResourceIDs).isEmpty();
    }

    @Test
    void kafkaServiceToProxyMapper() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaService kafkaServiceRef = buildKafkaService("ref", 1L, 1L);
        KafkaProxy proxy = buildProxy("proxy");
        VirtualKafkaCluster cluster = buildVirtualKafkaCluster(proxy, "cluster", kafkaServiceRef);

        KubernetesResourceList<KafkaProxy> mockProxyList = mockKafkaProxyListOperation(client);
        when(mockProxyList.getItems()).thenReturn(List.of(proxy));

        KubernetesResourceList<VirtualKafkaCluster> mockClusterList = mockVirtualKafkaClusterListOperation(client);
        when(mockClusterList.getItems()).thenReturn(List.of(cluster));

        SecondaryToPrimaryMapper<KafkaService> mapper = KafkaProxyReconciler.kafkaServiceRefToProxyMapper(eventSourceContext);

        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(kafkaServiceRef);
        assertThat(primaryResourceIDs).containsExactly(ResourceID.fromResource(proxy));
    }

    @Test
    void kafkaServiceToProxyMapperIgnoresServiceWithStaleStatus() {
        // given
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaService kafkaServiceRef = buildKafkaService("ref", 5L, 3L);
        KafkaProxy proxy = buildProxy("proxy");
        VirtualKafkaCluster cluster = buildVirtualKafkaCluster(proxy, "cluster", kafkaServiceRef);

        KubernetesResourceList<KafkaProxy> mockProxyList = mockKafkaProxyListOperation(client);
        when(mockProxyList.getItems()).thenReturn(List.of(proxy));

        KubernetesResourceList<VirtualKafkaCluster> mockClusterList = mockVirtualKafkaClusterListOperation(client);
        when(mockClusterList.getItems()).thenReturn(List.of(cluster));

        SecondaryToPrimaryMapper<KafkaService> mapper = KafkaProxyReconciler.kafkaServiceRefToProxyMapper(eventSourceContext);

        // when
        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(kafkaServiceRef);

        // then
        assertThat(primaryResourceIDs).isEmpty();
    }

    @Test
    void kafkaServiceToProxyMapperHandlesSharedKafkaService() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaService kafkaServiceRef = buildKafkaService("ref", 1L, 1L);
        KafkaProxy proxy1 = buildProxy("proxy1");
        KafkaProxy proxy2 = buildProxy("proxy2");
        VirtualKafkaCluster proxy1cluster = buildVirtualKafkaCluster(proxy1, "proxy1cluster", kafkaServiceRef);
        VirtualKafkaCluster proxy2cluster = buildVirtualKafkaCluster(proxy2, "proxy2cluster", kafkaServiceRef);

        KubernetesResourceList<KafkaProxy> mockProxyList = mockKafkaProxyListOperation(client);
        when(mockProxyList.getItems()).thenReturn(List.of(proxy1, proxy2));

        KubernetesResourceList<VirtualKafkaCluster> mockClusterList = mockVirtualKafkaClusterListOperation(client);
        when(mockClusterList.getItems()).thenReturn(List.of(proxy1cluster, proxy2cluster));

        SecondaryToPrimaryMapper<KafkaService> mapper = KafkaProxyReconciler.kafkaServiceRefToProxyMapper(eventSourceContext);

        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(kafkaServiceRef);
        assertThat(primaryResourceIDs).containsExactly(ResourceID.fromResource(proxy1), ResourceID.fromResource(proxy2));
    }

    @Test
    void kafkaServiceToProxyMapperHandlesOrphanKafkaService() {
        EventSourceContext<KafkaProxy> eventSourceContext = mock();
        KubernetesClient client = mock();
        when(eventSourceContext.getClient()).thenReturn(client);

        KafkaService orphanKafkaClusterRed = buildKafkaService("orphan", 1L, 1L);
        KafkaProxy proxy = buildProxy("proxy");
        VirtualKafkaCluster cluster = buildVirtualKafkaCluster(proxy, "cluster", buildKafkaService("ref", 1L, 1L));

        KubernetesResourceList<KafkaProxy> mockProxyList = mockKafkaProxyListOperation(client);
        when(mockProxyList.getItems()).thenReturn(List.of(proxy));

        KubernetesResourceList<VirtualKafkaCluster> mockClusterList = mockVirtualKafkaClusterListOperation(client);
        when(mockClusterList.getItems()).thenReturn(List.of(cluster));

        SecondaryToPrimaryMapper<KafkaService> mapper = KafkaProxyReconciler.kafkaServiceRefToProxyMapper(eventSourceContext);

        Set<ResourceID> primaryResourceIDs = mapper.toPrimaryResourceIDs(orphanKafkaClusterRed);
        assertThat(primaryResourceIDs).isEmpty();
    }

    @Test
    void shouldIncludeReadyReplicaCountInStatus() {
        // Given
        //@formatter:off
        KafkaProxy proxy = proxyBuilder("proxy")
                .withNewSpec()
                    .withReplicas(3)
                .endSpec()
                .build();
        //@formatter:on

        //@formatter:off
        var deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName("deployment")
                .endMetadata()
                .withNewStatus()
                    .withReplicas(3)
                    .withReadyReplicas(2)
                .endStatus()
                .build();
        //@formatter:on

        when(reconcilerContext.getSecondaryResource(Deployment.class, KafkaProxyReconciler.DEPLOYMENT_DEP)).thenReturn(Optional.of(deployment));

        // When
        UpdateControl<KafkaProxy> updateControl = newKafkaProxyReconciler(TEST_CLOCK).reconcile(proxy, reconcilerContext);

        // Then
        assertThat(updateControl).isNotNull()
                .extracting(UpdateControl::getResource, InstanceOfAssertFactories.OPTIONAL)
                .isPresent()
                .get(InstanceOfAssertFactories.type(KafkaProxy.class))
                .satisfies(kp -> {
                    assertThat(kp).isNotNull();
                    assertThat(kp.getStatus())
                            .isNotNull()
                            .extracting(KafkaProxyStatus::getReplicas)
                            .isEqualTo(2);
                });
    }

    @Test
    void shouldHandleDeploymentWithoutStatus() {
        // Given
        //@formatter:off
        KafkaProxy proxy = proxyBuilder("proxy")
                .withNewSpec()
                    .withReplicas(3)
                .endSpec()
                .build();
        //@formatter:on

        //@formatter:off
        var deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName("deployment")
                .endMetadata()
                .build();
        //@formatter:on

        when(reconcilerContext.getSecondaryResource(Deployment.class, KafkaProxyReconciler.DEPLOYMENT_DEP)).thenReturn(Optional.of(deployment));

        // When
        UpdateControl<KafkaProxy> updateControl = newKafkaProxyReconciler(TEST_CLOCK).reconcile(proxy, reconcilerContext);

        // Then
        assertThat(updateControl).isNotNull()
                .extracting(UpdateControl::getResource, InstanceOfAssertFactories.OPTIONAL)
                .isPresent()
                .get(InstanceOfAssertFactories.type(KafkaProxy.class))
                .satisfies(kp -> {
                    assertThat(kp).isNotNull();
                    assertThat(kp.getStatus())
                            .isNotNull()
                            .extracting(KafkaProxyStatus::getReplicas)
                            .isEqualTo(0); // No status implies no replicas running
                });
    }

    @Test
    void shouldHandleMissingDeployment() {
        // Given
        //@formatter:off
        KafkaProxy proxy = proxyBuilder("proxy")
                .withNewSpec()
                    .withReplicas(3)
                .endSpec()
                .build();
        //@formatter:on

        when(reconcilerContext.getSecondaryResource(Deployment.class, KafkaProxyReconciler.DEPLOYMENT_DEP)).thenReturn(Optional.empty());

        // When
        UpdateControl<KafkaProxy> updateControl = newKafkaProxyReconciler(TEST_CLOCK).reconcile(proxy, reconcilerContext);

        // Then
        assertThat(updateControl).isNotNull()
                .extracting(UpdateControl::getResource, InstanceOfAssertFactories.OPTIONAL)
                .isPresent()
                .get(InstanceOfAssertFactories.type(KafkaProxy.class))
                .satisfies(kp -> {
                    assertThat(kp).isNotNull();
                    assertThat(kp.getStatus())
                            .isNotNull()
                            .extracting(KafkaProxyStatus::getReplicas)
                            .isEqualTo(0); // No status implies no replicas running
                });
    }

    private KafkaProxy buildProxy(String name) {
        return proxyBuilder(name).build();
    }

    private KafkaProxyBuilder proxyBuilder(String name) {
        return new KafkaProxyBuilder().withNewMetadata().withName(name).endMetadata();
    }

    private KafkaService buildKafkaService(String name, long generation, long observedGeneration) {
        // @formatter:off
        return new KafkaServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withGeneration(generation)
                .endMetadata()
                .withNewStatus()
                    .withObservedGeneration(observedGeneration)
                .endStatus()
                .build();
        // @formatter:on
    }

    private VirtualKafkaCluster buildVirtualKafkaCluster(KafkaProxy kafkaProxy, String name, KafkaService clusterRef) {
        return baseVirtualKafkaClusterBuilder(kafkaProxy, name, 1L, 1L)
                .editOrNewSpec()
                .withNewTargetKafkaServiceRef()
                .withName(name(clusterRef))
                .endTargetKafkaServiceRef()
                .endSpec()
                .build();
    }

    private VirtualKafkaClusterBuilder baseVirtualKafkaClusterBuilder(KafkaProxy kafkaProxy, String name, long generation, long observedGeneration) {
        return new VirtualKafkaClusterBuilder()
                .withNewMetadata()
                .withName(name)
                .withGeneration(generation)
                .endMetadata()
                .withNewSpec()
                .withNewProxyRef()
                .withName(name(kafkaProxy))
                .endProxyRef().endSpec()
                .editStatus().withObservedGeneration(observedGeneration)
                .endStatus();
    }

    private KubernetesResourceList<KafkaProxy> mockKafkaProxyListOperation(KubernetesClient client) {
        MixedOperation<KafkaProxy, KubernetesResourceList<KafkaProxy>, Resource<KafkaProxy>> mockOperation = mock();
        when(client.resources(KafkaProxy.class)).thenReturn(mockOperation);

        KubernetesResourceList<KafkaProxy> mockList = mock();
        when(mockOperation.list()).thenReturn(mockList);
        when(mockOperation.inNamespace(any())).thenReturn(mockOperation);
        return mockList;
    }

    private KubernetesResourceList<VirtualKafkaCluster> mockVirtualKafkaClusterListOperation(KubernetesClient client) {
        MixedOperation<VirtualKafkaCluster, KubernetesResourceList<VirtualKafkaCluster>, Resource<VirtualKafkaCluster>> clusterOperation = mock();
        when(client.resources(VirtualKafkaCluster.class)).thenReturn(clusterOperation);

        KubernetesResourceList<VirtualKafkaCluster> clusterList = mock();
        when(clusterOperation.list()).thenReturn(clusterList);
        when(clusterOperation.inNamespace(any())).thenReturn(clusterOperation);
        return clusterList;
    }

    private KubernetesResourceList<KafkaService> mockKafkaServiceListOperation(KubernetesClient client) {
        MixedOperation<KafkaService, KubernetesResourceList<KafkaService>, Resource<KafkaService>> clusterRefOperation = mock();
        when(client.resources(KafkaService.class)).thenReturn(clusterRefOperation);

        KubernetesResourceList<KafkaService> clusterRefList = mock();
        when(clusterRefOperation.list()).thenReturn(clusterRefList);
        when(clusterRefOperation.inNamespace(any())).thenReturn(clusterRefOperation);
        return clusterRefList;
    }

}

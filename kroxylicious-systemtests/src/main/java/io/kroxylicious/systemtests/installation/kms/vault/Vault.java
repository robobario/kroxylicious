/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.installation.kms.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ServicePort;

import io.kroxylicious.systemtests.Environment;
import io.kroxylicious.systemtests.k8s.exception.KubeClusterException;
import io.kroxylicious.systemtests.resources.manager.ResourceManager;
import io.kroxylicious.systemtests.utils.DeploymentUtils;
import io.kroxylicious.systemtests.utils.NamespaceUtils;
import io.kroxylicious.systemtests.utils.TestUtils;

import static io.kroxylicious.systemtests.k8s.KubeClusterResource.getInstance;
import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;

/**
 * The type Vault.
 */
public class Vault {
    public static final String VAULT_SERVICE_NAME = "vault";
    public static final String VAULT_POD_NAME = VAULT_SERVICE_NAME + "-0";
    public static final String VAULT_DEFAULT_NAMESPACE = "vault";
    public static final String VAULT_HELM_REPOSITORY_URL = "https://helm.releases.hashicorp.com";
    public static final String VAULT_HELM_REPOSITORY_NAME = "hashicorp";
    public static final String VAULT_HELM_CHART_NAME = "hashicorp/vault";
    private static final Logger LOGGER = LoggerFactory.getLogger(Vault.class);
    private static final String VAULT_CMD = "vault";
    private final String deploymentNamespace;
    private final String vaultRootToken;

    /**
     * Instantiates a new Vault.
     *
     * @param vaultRootToken root token to be used for the vault install
     */
    public Vault(String vaultRootToken) {
        this.deploymentNamespace = VAULT_DEFAULT_NAMESPACE;
        this.vaultRootToken = vaultRootToken;
    }

    /**
     * Is deployed
     *
     * @return true if Vault service is deployed in kubernetes, false otherwise
     */
    public boolean isDeployed() {
        return kubeClient().getService(deploymentNamespace, VAULT_SERVICE_NAME) != null;
    }

    /**
     * Is available.
     *
     * @return true if Vault service is available in kubernetes, false otherwise
     */
    public boolean isAvailable() {
        if (!isDeployed()) {
            return false;
        }
        try (var output = new ByteArrayOutputStream();
                var exec = kubeClient().getClient().pods()
                        .inNamespace(deploymentNamespace)
                        .withName(VAULT_POD_NAME)
                        .writingOutput(output)
                        .exec("sh", "-c", VAULT_CMD + " operator init -status")) {
            int exitCode = exec.exitCode().join();
            return exitCode == 0 &&
                    output.toString().toLowerCase().contains("vault is initialized");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Gets the installed version.
     *
     * @return the version
     */
    public String getVersionInstalled() {
        try (var output = new ByteArrayOutputStream();
                var error = new ByteArrayOutputStream();
                var exec = kubeClient().getClient().pods()
                        .inNamespace(deploymentNamespace)
                        .withName(VAULT_POD_NAME)
                        .writingOutput(output)
                        .writingError(error)
                        .exec("sh", "-c", VAULT_CMD + " version")) {
            int exitCode = exec.exitCode().join();
            if (exitCode != 0) {
                throw new UnsupportedOperationException(error.toString());
            }
            // version returned with format: Vault v1.15.2 (blah blah), build blah
            String version = output.toString().split("\\s+")[1].replace("v", "");
            if (!version.matches("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\*|\\d+))?$")) {
                throw new NumberFormatException("Invalid version format: " + version);
            }
            return version;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deploy.
     *
     */
    public void deploy() {
        if (isDeployed()) {
            LOGGER.warn("Skipping Vault deployment. It is already deployed!");
            return;
        }

        boolean openshiftCluster = getInstance().isOpenshift();
        LOGGER.info("Deploy HashiCorp Vault in {} namespace, openshift: {}", deploymentNamespace, openshiftCluster);

        NamespaceUtils.createNamespaceWithWait(deploymentNamespace);
        ResourceManager.helmClient().addRepository(VAULT_HELM_REPOSITORY_NAME, VAULT_HELM_REPOSITORY_URL);
        ResourceManager.helmClient().namespace(deploymentNamespace).install(VAULT_HELM_CHART_NAME, VAULT_SERVICE_NAME,
                Optional.of(Environment.VAULT_CHART_VERSION),
                Optional.of(Path.of(TestUtils.getResourcesURI("helm_vault_overrides.yaml"))),
                Optional.of(Map.of("server.dev.devRootToken", vaultRootToken,
                        "global.openshift", String.valueOf(openshiftCluster))));

        DeploymentUtils.waitForDeploymentRunning(deploymentNamespace, VAULT_POD_NAME, Duration.ofMinutes(1));
    }

    /**
     * Delete.
     *
     * @throws IOException the io exception
     */
    public void delete() throws IOException {
        LOGGER.info("Deleting Vault in {} namespace", deploymentNamespace);
        NamespaceUtils.deleteNamespaceWithWait(deploymentNamespace);
    }

    /**
     * Gets the vault url.
     *
     * @return the vault url.
     */
    public String getVaultUrl() {
        var spec = kubeClient().getService(deploymentNamespace, VAULT_SERVICE_NAME).getSpec();
        String clusterIP = spec.getClusterIP();
        if (clusterIP == null || clusterIP.isEmpty()) {
            throw new KubeClusterException("Unable to get the clusterIP of Vault");
        }
        int port = spec.getPorts().stream().map(ServicePort::getPort).findFirst()
                .orElseThrow(() -> new KubeClusterException("Unable to get the service port of Vault"));
        String url = clusterIP + ":" + port;
        LOGGER.debug("Vault URL: {}", url);
        return url;
    }
}

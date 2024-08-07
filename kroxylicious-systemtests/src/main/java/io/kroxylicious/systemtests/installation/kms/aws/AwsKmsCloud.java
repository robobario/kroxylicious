/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.installation.kms.aws;

import java.net.URI;

import io.kroxylicious.systemtests.Environment;
import io.kroxylicious.systemtests.executor.Exec;
import io.kroxylicious.systemtests.executor.ExecResult;

/**
 * The type Aws kms cloud.
 */
public class AwsKmsCloud implements AwsKmsClient {
    private static final String AWS_CMD = "aws";
    private String region;

    /**
     * Instantiates a new Aws.
     *
     */
    public AwsKmsCloud() {
        // nothing to initialize
    }

    @Override
    public String getAwsCmd() {
        return AWS_CMD;
    }

    @Override
    public boolean isAvailable() {
        return Environment.AWS_USE_CLOUD.equalsIgnoreCase("true");
    }

    @Override
    public void deploy() {
        // nothing to deploy
    }

    @Override
    public void delete() {
        // nothing to delete
    }

    @Override
    public URI getAwsUrl() {
        return URI.create("https://kms." + getRegion() + ".amazonaws.com");
    }

    @Override
    public String getRegion() {
        if (region == null) {
            ExecResult execResult = Exec.exec(AWS_CMD, "configure", "get", "region");
            if (!execResult.isSuccess()) {
                throw new UnknownError(execResult.err());
            }
            region = execResult.out().trim();
        }

        return region;
    }
}

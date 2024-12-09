/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.validation.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for Produce Request validation. Contains a description of the rules for validating
 * the data for all topic-partitions with a ProduceRequest and how to handle partial failures (where
 * some topic-partitions are valid and others are invalid within a single ProduceRequest)
 *
 * @param rules describes a list of rules, associating topics with some validation to be applied to produce data for that topic
 * @param defaultRule the default validation rule to be applied when no rule is matched for a topic within a ProduceRequest
 */
public record ValidationConfig(List<TopicMatchingRecordValidationRule> rules, RecordValidationRule defaultRule) {

    @JsonCreator
    public ValidationConfig(@JsonProperty("rules") List<TopicMatchingRecordValidationRule> rules,
                            @JsonProperty("defaultRule") RecordValidationRule defaultRule) {
        this.rules = rules;
        this.defaultRule = defaultRule;
    }

    /**
     * Get the rules
     * @return rules
     */
    @Override
    public List<TopicMatchingRecordValidationRule> rules() {
        return rules;
    }

    /**
     * get default rule
     * @return default rule (not null)
     */
    @Override
    public RecordValidationRule defaultRule() {
        return defaultRule;
    }

    @Override
    public String toString() {
        return "ValidationConfig{" +
                ", rules=" + rules +
                ", defaultRule=" + defaultRule +
                '}';
    }
}

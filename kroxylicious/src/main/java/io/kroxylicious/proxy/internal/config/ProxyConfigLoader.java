/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.config;

import java.util.ServiceLoader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import io.kroxylicious.proxy.config.BaseConfig;
import io.kroxylicious.proxy.filter.FilterContributor;
import io.kroxylicious.proxy.internal.config.model.ProxyConfig;

public class ProxyConfigLoader {
    /*
     * adminHttp:
     * endpoints:
     * prometheus: {}
     * virtualClusters:
     * demo:
     * targetCluster:
     * bootstrap_servers: localhost:9092
     * clusterNetworkAddressConfigProvider:
     * type: PortPerBroker
     * config:
     * bootstrapAddress: localhost:9192
     * logNetwork: false
     * logFrames: false
     * filters:
     * fromDirectory: "filters.d"
     * fromTopic:
     * fromTopic:
     * consumerConfig:
     */

    /*
     * And them the filters are like files like 10-ReplaceFooWithBar.yml
     * type: org.example.SampleProduceRequest
     * config:
     * findValue: foo
     * replacementValue: bar
     *
     */

    public static void main(String[] args) throws JsonProcessingException {
        YAMLMapper mapper = new YAMLMapper();

        // We load the config file
        // Note that filters is not literal, but supports a number of sources
        // We can implment support for loading filters from a .d directory, in classic unix style
        // Or we can just read from a Kafka Topic (which makes is easier to do dynamic reconfig in a container env)
        var pc = mapper.readValue("""
                adminHttp:
                  endpoints:
                    prometheus: "{}"

                filters:
                  fromDirectory: "filters.d"
                """, ProxyConfig.class);

        // We then load each of the filters. Here's an example file/record value
        var tree = mapper.readTree("""
                type: io.kroxylicious.proxy.internal.filter.FetchResponseTransformationFilter
                # type is the FQ class name. Maybe we support the unqualified class name for conventience, like Kafka Connect, but it's always based on the class name
                # and not something the filter class itself is able to override
                config:
                  transformation: io.kroxylicious.proxy.internal.filter.ProduceRequestTransformationFilter$UpperCasing""");
        var filterClassName = tree.get("type").asText();

        // We load the services. If we support filter isolation here we need to use the load() override which takes a classloader
        var services = ServiceLoader.load(FilterContributor.class);

        // The FilterContributor has a getFilterType method that returns the Class of the filters it constructs
        // We use this to find a match for a given filter YAML
        var contributor = services.stream()
                .map(ServiceLoader.Provider::get)
                .filter(fc -> {
                    return fc.getFilterType().getName().equals(filterClassName);
                }).findFirst().get();

        // We convert the TreeNode for the `config` part of the YAML to the Java config representation
        Class configClass = contributor.getConfigClass();
        var config = (BaseConfig) mapper.treeToValue(tree.get("config"), configClass);
        // Note AFAICS there's not need for BaseConfig here at all

        // We instantiate a filter
        contributor.getInstance(config, null);

    }
}

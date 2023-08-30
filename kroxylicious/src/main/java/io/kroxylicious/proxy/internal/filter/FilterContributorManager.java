/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import io.kroxylicious.proxy.config.BaseConfig;
import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterConstructContext;
import io.kroxylicious.proxy.filter.FilterContributor;

public class FilterContributorManager {

    private static final FilterContributorManager INSTANCE = new FilterContributorManager();

    public static final String DEFAULT_PLUGIN_ROOT = "/opt/kroxylicious/plugins";

    private final List<FilterContributor> contributors;
    private final Path pluginRoot;

    public static FilterContributorManager getInstance() {
        return INSTANCE;
    }

    public FilterContributorManager() {
        pluginRoot = Path.of(DEFAULT_PLUGIN_ROOT);
        contributors = discoverContributors();
    }

    private List<FilterContributor> discoverContributors() {
        List<FilterContributor> filterContributors = new ArrayList<>();
        Map<String, FilterContributor> rootClassloaderContributors = discoverFilterContributors(Map.of(), Thread.currentThread().getContextClassLoader());
        Map<String, FilterContributor> pluginContributors;
        if (Files.isDirectory(pluginRoot)) {
            pluginContributors = discoverFilterContributorsInRoot(rootClassloaderContributors, pluginRoot);
        }
        else {
            pluginContributors = Map.of();
        }
        filterContributors.addAll(rootClassloaderContributors.values());
        filterContributors.addAll(pluginContributors.values());
        return filterContributors;
    }

    private Map<String, FilterContributor> discoverFilterContributorsInRoot(Map<String, FilterContributor> rootClassloaderContributors, Path pluginRoot) {
        try {
            try (Stream<Path> listed = Files.list(pluginRoot)) {
                List<Path> filterDirectories = listed.filter(Files::isDirectory).toList();
                List<Map<String, FilterContributor>> list = filterDirectories.stream().map(path -> discoverFilterContributorsInDir(rootClassloaderContributors, path))
                        .toList();

                // todo barf if the same filter contributor present in multiple directories
                return list.stream().reduce(new HashMap<>(), (hashMap, stringFilterContributorMap) -> {
                    hashMap.putAll(stringFilterContributorMap);
                    return hashMap;
                });
            }

        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, FilterContributor> discoverFilterContributorsInDir(Map<String, FilterContributor> rootClassloaderContributors, Path dir) {
        try {
            List<Path> jars = Files.list(dir).filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
            if (jars.isEmpty()) {
                return Map.of();
            }
            else {
                return discoverFilterContributors(rootClassloaderContributors, createClassloader(jars));
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ClassLoader createClassloader(List<Path> jars) {
        URL[] array = jars.stream().map(jar -> {
            try {
                return jar.toUri().toURL();
            }
            catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }).toArray(URL[]::new);
        return new URLClassLoader(array, Thread.currentThread().getContextClassLoader());
    }

    private Map<String, FilterContributor> discoverFilterContributors(Map<String, FilterContributor> rootContributors, ClassLoader contextClassLoader) {
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
            Map<String, FilterContributor> contributorMap = new HashMap<>();
            for (FilterContributor contributor : ServiceLoader.load(FilterContributor.class)) {
                String name = contributor.getClass().getName();
                if (!rootContributors.containsKey(name)) {
                    contributorMap.put(name, contributor);
                }
            }
            return contributorMap;
        }
        finally {
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
    }

    public Class<? extends BaseConfig> getConfigType(String shortName) {
        Iterator<FilterContributor> it = contributors.iterator();
        while (it.hasNext()) {
            FilterContributor contributor = it.next();
            Class<? extends BaseConfig> configType = contributor.getConfigType(shortName);
            if (configType != null) {
                return configType;
            }
        }

        throw new IllegalArgumentException("No filter found for name '" + shortName + "'");
    }

    public Filter getFilter(String shortName, NettyFilterContext context, BaseConfig filterConfig) {
        Iterator<FilterContributor> it = contributors.iterator();
        while (it.hasNext()) {
            FilterContributor contributor = it.next();
            FilterConstructContext context1 = context.wrap(filterConfig);
            Filter filter = contributor.getInstance(shortName, context1);
            if (filter != null) {
                return filter;
            }
        }

        throw new IllegalArgumentException("No filter found for name '" + shortName + "'");
    }

}

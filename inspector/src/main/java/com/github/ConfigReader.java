package com.github;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigReader {

    private Environment env;

    public ConfigReader(Environment env) {
        this.env = env;
    }

    public String getDirectory() {
        return env.getProperty("inspector.directory");
    }

    public Map<String, List<String>> extractMappings() {
        var map = new HashMap<String, List<String>>();

        if (env instanceof ConfigurableEnvironment) {
            var configurableEnv = ((ConfigurableEnvironment) env);


            for (PropertySource<?> propertySource : configurableEnv.getPropertySources()) {
                if (propertySource instanceof EnumerablePropertySource) {
                    var enumerablePropertySource = (EnumerablePropertySource<?>) propertySource;
                    for (String key : enumerablePropertySource.getPropertyNames()) {
                        if (key.startsWith("inspector.bean")) {
                            String[] values = configurableEnv.getProperty(key).split(",");
                            map.put(key.replace("inspector.bean.", ""), Arrays.asList(values));
                        }
                    }
                }
            }
        }
        return map;
    }
}

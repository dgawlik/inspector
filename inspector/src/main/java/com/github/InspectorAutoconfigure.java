package com.github;

import com.google.gson.Gson;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

@AutoConfiguration
public class InspectorAutoconfigure {


    @Configuration
    public static class InspectorConfiguration implements ApplicationContextAware, Ordered {

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

            var env = applicationContext.getEnvironment();

            var availableMappings = new ConfigReader(env).extractMappings();
            var dirPath = new ConfigReader(env).getDirectory();

            var instrumentation = new Instrumentation();

            instrumentation.instrumentAll(applicationContext, availableMappings, new JsonWriter(dirPath));
        }


        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

    }


}

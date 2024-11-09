package com.github;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

@AutoConfiguration(before = TransactionAutoConfiguration.class)
@ConditionalOnProperty(prefix = "inspector", name = "enabled", havingValue = "true")
public class InspectorAutoconfigure {

    public static class MyMethodInterceptor implements MethodInterceptor {

        private String directory;

        public MyMethodInterceptor(String directory) {
            this.directory = directory;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            var className = invocation.getMethod().getDeclaringClass().getSimpleName();
            var methodName = invocation.getMethod().getName();
            var timestamp = System.currentTimeMillis();

            Files.writeString(Path.of(directory + "/" + className + "_" + methodName + "_" + timestamp + "_args.json"), new Gson().toJson(invocation.getArguments()));

            Object result = invocation.proceed();

            Files.writeString(Path.of(directory + "/" + className + "_" + methodName + "_" + timestamp + "_result.json"), new Gson().toJson(result));

            return result;
        }
    }


    @Configuration
    public static class InspectorPostProcessor {

        @Autowired
        private Environment env;


        @Bean
        public PointcutAdvisor pointcutAdvisor() {
            ComposablePointcut composed = null;

            var config = new ConfigReader(env);
            var mappings = config.extractMappings();

            for (var key : mappings.keySet()) {
                var pointcut = new NameMatchMethodPointcut();
                pointcut.setMappedNames(mappings.get(key).toArray(new String[0]));
                pointcut.setClassFilter(clazz -> assignable(clazz, key));
                if (composed == null) {
                    composed = new ComposablePointcut((Pointcut) pointcut);
                } else {
                    composed = composed.union((Pointcut) pointcut);
                }
            }

            Advice advice = new MyMethodInterceptor(config.getDirectory());
            return new DefaultPointcutAdvisor(composed, advice);
        }

        private boolean assignable(Class<?> clazz, String name) {
            if (clazz.getSimpleName().equals(name)) {
                return true;
            } else {
                for (var i : clazz.getInterfaces()) {
                    if (assignable(i, name)) {
                        return true;
                    }
                }

               if (clazz.getSuperclass() != null && assignable(clazz.getSuperclass(), name)) {
                   return true;
               }

               return false;
            }
        }
    }
}

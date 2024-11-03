package com.github;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

public class Instrumentation {

    public static class CallResult {
        public Object result;
        public String targetName;
        public String methodName;

        public CallResult(Object result, String targetName, String methodName) {
            this.result = result;
            this.targetName = targetName;
            this.methodName = methodName;
        }


    }

    public static class InvocationHandler implements java.lang.reflect.InvocationHandler {
        private Object target;
        private boolean done = false;
        private Consumer<CallResult> callback;
        private String beanName;
        private List<String> methodNames;

        public InvocationHandler(Object bean, String beanName, List<String> methodNames, Consumer<CallResult> callback) {
            this.target = bean;
            this.callback = callback;
            this.beanName = beanName;
            this.methodNames = methodNames;
        }


        public synchronized Object invoke(Object obj, Method method, Object[] args) throws Throwable {

            Object result = method.invoke(target, args);

            if (!done && methodNames.contains(method.getName())) {
                done = true;
                callback.accept(new CallResult(result, beanName, method.getName()));
                return result;
            }
            return result;

        }
    }


    public class Interceptor {
        private Consumer<CallResult> callback;
        private String beanName;
        private List<String> methodNames;

        public Interceptor(String beanName, List<String> methodNames, Consumer<CallResult> callback) {
            this.callback = callback;
            this.beanName = beanName;
            this.methodNames = methodNames;
        }

        @RuntimeType
        public Object intercept(@This Object self,
                                @Origin Method method,
                                @AllArguments Object[] args,
                                @SuperMethod Method superMethod) throws Throwable {

            var result = superMethod.invoke(self, args);
            if (methodNames.contains(method.getName())) {
                callback.accept(new CallResult(result, beanName, method.getName()));
            }
            return result;
        }
    }

    public void instrumentRecurse(ApplicationContext ctx,
                                  Map<String, List<String>> mappings,
                                  Consumer<CallResult> callback,
                                  Map<String, List<String>> graph,
                                  Set<String> visited,
                                  String parent,
                                  String current){
        if(visited.contains(current)){
            return;
        }

        visited.add(current);

        if(graph.containsKey(current)){
            for(var child : graph.get(current)){
                instrumentRecurse(ctx, mappings, callback, graph, visited, current, child);
            }
        }

        try {
            beanFactory(ctx).autowireBean(ctx.getBean(current));
            var beanName = current;
            var methodNames = mappings.get(beanName);

            if(methodNames != null) {
                var bean = instrument(ctx, callback, beanName, methodNames);

                beanFactory(ctx).removeBeanDefinition(beanName);
                beanFactory(ctx).registerSingleton(beanName, bean);

                if (parent != null) {
                    beanFactory(ctx).autowireBean(beanFactory(ctx).getBean(parent));
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public void instrumentAll(ApplicationContext ctx, Map<String, List<String>> mappings, Consumer<CallResult> callback) {

        Map<String, List<String>> dependencyGraph = new HashMap<>();

        for (String beanName : mappings.keySet()) {
            dependencyGraph.put(beanName, Arrays.asList(beanFactory(ctx).getDependenciesForBean(beanName)));
        }

        for (var otherBean : beanFactory(ctx).getBeanDefinitionNames()) {
            var deps = Arrays.asList(beanFactory(ctx).getDependenciesForBean(otherBean));
            if (deps.stream().anyMatch(mappings::containsKey)) {
                dependencyGraph.put(otherBean, deps.stream().filter(mappings::containsKey).toList());
            }
        }


        var visited = new HashSet<String>();
        for (var key : dependencyGraph.keySet()) {
            instrumentRecurse(ctx, mappings, callback, dependencyGraph, visited, null, key);
        }

        beanFactory(ctx).getBeanNamesIterator().forEachRemaining(otherBean -> {
            if (!otherBean.equals("com.github.InspectorAutoconfigure$InspectorConfiguration")) {
                var other = ctx.getBean(otherBean);
                beanFactory(ctx).autowireBean(other);
            }
        });
    }

    private Object instrument(ApplicationContext ctx, Consumer<CallResult> callback, String beanName, List<String> methodNames) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        Object bean = ctx.getBean(beanName);

        int modifiers = bean.getClass().getModifiers();
        boolean isFinal = Modifier.isFinal(modifiers);

        if (bean.getClass().getInterfaces().length > 0) {
            bean = Proxy.newProxyInstance(
                    bean.getClass().getClassLoader(),
                    bean.getClass().getInterfaces(),
                    new InvocationHandler(bean, beanName, methodNames, callback)
            );
        } else if (!isFinal) {
            bean = new ByteBuddy()
                    .subclass(ctx.getType(beanName))
                    .method(ElementMatchers.any()).intercept(MethodDelegation.to(new Interceptor(beanName, methodNames, callback)))
                    .make()
                    .load(this.getClass().getClassLoader())
                    .getLoaded()
                    .getDeclaredConstructor()
                    .newInstance();

        } else {
            throw new RuntimeException("Cannot instrument final class");
        }
        return bean;
    }

    private DefaultListableBeanFactory beanFactory(ApplicationContext ctx) {
        if (ctx instanceof ConfigurableApplicationContext) {
            return (DefaultListableBeanFactory) ((ConfigurableApplicationContext) ctx).getBeanFactory();
        }
        return null;
    }
}

package com.github;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Instrumentation {

    public static class Call {
        public Object result;
        public Object[] args;
        public String targetName;
        public String methodName;
        public Long timestamp;

        public Call(Object result, Object[] args, String targetName, String methodName) {
            this.result = result;
            this.args = args;
            this.targetName = targetName;
            this.methodName = methodName;
            this.timestamp = System.currentTimeMillis();
        }


    }

    public static class InvocationHandler implements java.lang.reflect.InvocationHandler {
        private Object target;
        private boolean done = false;
        private Consumer<Call> callback;
        private String beanName;
        private List<String> methodNames;

        public InvocationHandler(Object bean, String beanName, List<String> methodNames, Consumer<Call> callback) {
            this.target = bean;
            this.callback = callback;
            this.beanName = beanName;
            this.methodNames = methodNames;
        }


        public synchronized Object invoke(Object obj, Method method, Object[] args) throws Throwable {

            Object result = method.invoke(target, args);

            if (!done && methodNames.contains(method.getName())) {
                done = true;
                callback.accept(new Call(result, args, beanName, method.getName()));
                return result;
            }
            return result;

        }
    }


    public class Interceptor {
        private Consumer<Call> callback;
        private String beanName;
        private List<String> methodNames;

        public Interceptor(String beanName, List<String> methodNames, Consumer<Call> callback) {
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
                callback.accept(new Call(result, args, beanName, method.getName()));
            }
            return result;
        }
    }

    public void instrumentRecurse(ApplicationContext ctx,
                                  Map<String, List<String>> mappings,
                                  Consumer<Call> callback,
                                  Map<String, List<String>> graph,
                                  Set<String> visited,
                                  String parent,
                                  String current) {
        if (visited.contains(current)) {
            return;
        }

        if (graph.containsKey(current)) {
            for (var child : graph.get(current)) {
                instrumentRecurse(ctx, mappings, callback, graph, visited, current, child);
            }
        }

        try {

            var beanName = current;
            var methodNames = mappings.get(beanName);

            if (methodNames != null) {
                var bean = instrument(ctx, callback, beanName, methodNames);

                beanFactory(ctx).removeBeanDefinition(beanName);
                beanFactory(ctx).registerSingleton(beanName, bean);
                beanFactory(ctx).autowireBean(ctx.getBean(current));
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        visited.add(current);

    }

    public void instrumentAll(ApplicationContext ctx, Map<String, List<String>> mappings, Consumer<Call> callback) {

        Map<String, List<String>> dependencyGraph = new HashMap<>();

        for (String beanName : mappings.keySet()) {
            dependencyGraph.put(beanName, Arrays.asList(beanFactory(ctx).getDependenciesForBean(beanName)));
        }

        var allBeans = Arrays.asList(beanFactory(ctx).getBeanDefinitionNames());

        for (var otherBean : allBeans) {
            var deps = Arrays.asList(beanFactory(ctx).getDependenciesForBean(otherBean));
            if (deps.stream().anyMatch(mappings::containsKey)) {
                var root = deps.stream().filter(mappings::containsKey).toList();
                dependencyGraph.put(otherBean, root);
            }
        }

        var roots = dependencyGraph.keySet().stream().filter(
                        k -> dependencyGraph.values().stream()
                                .flatMap(List::stream).noneMatch(k::equals))
                .toList();


        class Dep {
            public String name;
            public List<String> deps;

            public Dep(String name, List<String> deps) {
                this.name = name;
                this.deps = deps;
            }
        }

        var dependant = roots
                .stream()
                .map(r -> new Dep(r, Arrays.stream(beanFactory(ctx).getDependentBeans(r)).toList()))
                .collect(Collectors.toMap(d -> d.name, d -> d.deps));

        while (dependant.values().stream().anyMatch(l -> !l.isEmpty())) {


            for (var dependency : dependant.keySet()) {
                if (dependency.isEmpty()) {
                    continue;
                }

                for (String dep : dependant.get(dependency)) {
                    if (dependencyGraph.containsKey(dep)) {
                        dependencyGraph.get(dep).add(dependency);
                    } else {
                        dependencyGraph.put(dep, new ArrayList<>(List.of(dependency)));
                    }
                }
            }

            roots = dependencyGraph.keySet().stream().filter(
                            k -> dependencyGraph.values().stream()
                                    .flatMap(List::stream).noneMatch(k::equals))
                    .toList();

        }


        var visited = new HashSet<String>();
        for (var key : roots) {
            instrumentRecurse(ctx, mappings, callback, dependencyGraph, visited, null, key);
        }
    }

    private Object instrument(ApplicationContext ctx, Consumer<Call> callback, String beanName, List<String> methodNames) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
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
            var beanClass = new ByteBuddy()
                    .subclass(ctx.getType(beanName))
                    .method(ElementMatchers.any()).intercept(MethodDelegation.to(new Interceptor(beanName, methodNames, callback)))
                    .make()
                    .load(this.getClass().getClassLoader())
                    .getLoaded();

            var ctors = beanClass.getConstructors();

            if (ctors.length == 1) {
                bean = ctors[0].newInstance(getConstructorArgs(ctx, ctors[0]));
            } else {
                var autowiredCtor = Arrays.stream(ctors).filter(c -> c.isAnnotationPresent(Autowired.class)).findFirst();
                if (autowiredCtor.isPresent()) {
                    bean = autowiredCtor.get().newInstance(getConstructorArgs(ctx, autowiredCtor.get()));
                } else {
                    throw new RuntimeException("No constructors suitable for autowiring");
                }
            }

        } else {
            throw new RuntimeException("Cannot instrument final class");
        }
        return bean;
    }

    private Object[] getConstructorArgs(ApplicationContext ctx, Constructor<?> ctor) {
        var args = new Object[ctor.getParameterCount()];
        for (int i = 0; i < ctor.getParameterCount(); i++) {
            var paramType = ctor.getParameterTypes()[i];
            var paramBean = ctx.getBean(paramType);
            args[i] = paramBean;
        }
        return args;
    }

    private DefaultListableBeanFactory beanFactory(ApplicationContext ctx) {
        if (ctx instanceof ConfigurableApplicationContext) {
            return (DefaultListableBeanFactory) ((ConfigurableApplicationContext) ctx).getBeanFactory();
        }
        return null;
    }
}

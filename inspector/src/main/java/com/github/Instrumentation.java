package com.github;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
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

    public void instrumentAll(ApplicationContext ctx, Map<String, List<String>> mappings, Consumer<CallResult> callback) {

        for (var entry : mappings.entrySet()) {

            String beanName = entry.getKey();
            Object bean = ctx.getBean(beanName);
            List<String> methodNames = entry.getValue();

            try {
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


                beanFactory(ctx).removeBeanDefinition(entry.getKey());
                beanFactory(ctx).registerSingleton(entry.getKey(), bean);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        beanFactory(ctx).getBeanNamesIterator().forEachRemaining(otherBean -> {
            if (!otherBean.equals("com.github.InspectorAutoconfigure$InspectorConfiguration")) {
                var other = ctx.getBean(otherBean);
                beanFactory(ctx).autowireBean(other);
            }
        });
    }

    private DefaultListableBeanFactory beanFactory(ApplicationContext ctx) {
        if (ctx instanceof ConfigurableApplicationContext) {
            return (DefaultListableBeanFactory) ((ConfigurableApplicationContext) ctx).getBeanFactory();
        }
        return null;
    }
}

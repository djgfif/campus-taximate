package com.campustaximate.server.common;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.*;
import org.springframework.aop.support.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

/** @Transactional 서비스의 트랜잭션 어드바이스보다 바깥에서 재시도한다. */
@Configuration
public class LockRetryConfiguration {
    @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    Advisor lockRetryAdvisor(){
        var attributes=new AnnotationTransactionAttributeSource();
        var pointcut=new StaticMethodMatcherPointcut(){
            @Override public boolean matches(java.lang.reflect.Method method,Class<?> target){
                return target.getPackageName().startsWith("com.campustaximate.server.service") && attributes.getTransactionAttribute(method,target)!=null;
            }
        };
        MethodInterceptor interceptor=invocation->LockRetry.run(()->((ProxyMethodInvocation)invocation).invocableClone().proceed());
        var advisor=new DefaultPointcutAdvisor(pointcut,interceptor);advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);return advisor;
    }
}

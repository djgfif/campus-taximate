package com.campustaximate.server.common;
import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import org.springframework.dao.*;import org.springframework.transaction.support.*;
class LockRetryTest {
    @Test void retriesLockFailureAtMostTwiceThenSucceeds(){
        var attempts=new AtomicInteger();String result=LockRetry.call(()->{if(attempts.incrementAndGet()<3)throw new CannotAcquireLockException("모의 교착");return "완료";});
        assertThat(result).isEqualTo("완료");assertThat(attempts.get()).isEqualTo(3);
    }
    @Test void sqlState40001ExhaustionBecomes409Retry(){
        var attempts=new AtomicInteger();assertThatThrownBy(()->LockRetry.call(()->{attempts.incrementAndGet();throw new RuntimeException(new SQLException("모의 교착","40001"));}))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(409);assertThat(e.getCode()).isEqualTo("RETRY");assertThat(e.getMessage()).isEqualTo("잠시 뒤 다시 시도해 주세요");});assertThat(attempts.get()).isEqualTo(3);
    }
    @Test void validationErrorsAreNotRetried(){
        var count=new AtomicInteger();assertThatThrownBy(()->LockRetry.call(()->{count.incrementAndGet();throw ApiException.badRequest("BAD","입력 오류");})).isInstanceOf(ApiException.class);assertThat(count.get()).isEqualTo(1);
    }
    @Test void everyRetryGetsANewTransactionAndRollsBackTheVictim(){
        var begins=new AtomicInteger();var rollbacks=new AtomicInteger();var commits=new AtomicInteger();
        var manager=new AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition d){begins.incrementAndGet();}
            @Override protected void doCommit(DefaultTransactionStatus s){commits.incrementAndGet();}
            @Override protected void doRollback(DefaultTransactionStatus s){rollbacks.incrementAndGet();}
        };
        var calls=new AtomicInteger();new RetryingTransactionTemplate(manager).executeWithoutResult(s->{if(calls.incrementAndGet()<3)throw new PessimisticLockingFailureException("모의 잠금 실패");});
        assertThat(begins.get()).isEqualTo(3);assertThat(rollbacks.get()).isEqualTo(2);assertThat(commits.get()).isEqualTo(1);
    }
    public static class TransactionalWriter {
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        @org.springframework.transaction.annotation.Transactional public String write(){if(calls.incrementAndGet()<3)throw new CannotAcquireLockException("모의 교착");return "완료";}
    }
    @Test void transactionalAdviceIsRetriedOutsideTheRollbackBoundary(){
        var begins=new AtomicInteger();var rollbacks=new AtomicInteger();var commits=new AtomicInteger();
        var manager=new AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition d){begins.incrementAndGet();}
            @Override protected void doCommit(DefaultTransactionStatus s){commits.incrementAndGet();}
            @Override protected void doRollback(DefaultTransactionStatus s){rollbacks.incrementAndGet();}
        };
        var target=new TransactionalWriter();var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new LockRetryConfiguration().lockRetryAdvisor().getAdvice());
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        assertThat(((TransactionalWriter)factory.getProxy()).write()).isEqualTo("완료");
        assertThat(target.calls.get()).isEqualTo(3);assertThat(begins.get()).isEqualTo(3);assertThat(rollbacks.get()).isEqualTo(2);assertThat(commits.get()).isEqualTo(1);
    }
}

package com.campustaximate.server.common;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
/** 콜백마다 새 영속성 컨텍스트를 열어 saveAndFlush와 커밋 실패도 다시 수행한다. */
public class RetryingTransactionTemplate extends TransactionTemplate {
    public RetryingTransactionTemplate(PlatformTransactionManager manager){super(manager);}
    @Override public <T>T execute(TransactionCallback<T> action){return LockRetry.call(()->super.execute(action));}
}

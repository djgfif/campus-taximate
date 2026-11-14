package com.campustaximate.server.common;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 교착 피해 트랜잭션을 재사용하지 않고 바깥 경계에서 최초 실행 + 두 번 재시도한다. */
public final class LockRetry {
    private LockRetry() {}
    @FunctionalInterface public interface Work { Object run() throws Throwable; }
    public static boolean transientFailure(Throwable error) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable t=error;t!=null && seen.add(t);t=t.getCause()) {
            if(t instanceof PessimisticLockingFailureException || t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException)return true;
            if(t instanceof SQLException sql) for(SQLException e=sql;e!=null;e=e.getNextException())if("40001".equals(e.getSQLState()))return true;
        }
        return false;
    }
    public static Object run(Work work) throws Throwable {
        if(TransactionSynchronizationManager.isActualTransactionActive())return work.run();
        for(int attempt=0;;attempt++)try{return work.run();}catch(Throwable failure){
            if(!transientFailure(failure))throw failure;
            if(attempt>=2)throw retryResponse();
        }
    }
    public static ApiException retryResponse(){return ApiException.conflict("RETRY","잠시 뒤 다시 시도해 주세요");}
    @SuppressWarnings("unchecked")
    public static <T>T call(Supplier<T> work){
        try{return (T)run(work::get);}catch(RuntimeException|Error e){throw e;}catch(Throwable e){throw new IllegalStateException(e);}
    }
}

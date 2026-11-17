package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.campustaximate.server.common.AppClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/** 같은 일회용 코드로 동시에 두 번 로그인하지 못한다. H2와 실제 MySQL 양쪽에서 돈다. */
abstract class AuthConcurrencyCase {

    private static final String RACER = "login-race-";

    @Autowired private AuthService auth;
    @SpyBean private AppClock clock;

    @Test
    void sameCodeLogsInOnlyOnceEvenWhenTwoRequestsReadTheMemberTogether() throws Exception {
        String email = "race" + System.nanoTime() + "@daejin.ac.kr";
        String code = auth.requestCode(email);

        // login()은 회원을 읽은 직후 시계를 본다. 그 첫 호출에서 두 요청이 서로를 잠깐 기다리게 해 경합 순서를 고정한다.
        // 회원을 잠그고 읽으면 두 번째 요청은 잠금에서 막혀 여기까지 오지 못하고, 첫 요청은 시간 초과 뒤 진행한다.
        CyclicBarrier bothRead = new CyclicBarrier(2);
        ThreadLocal<Boolean> paused = ThreadLocal.withInitial(() -> false);
        doAnswer(call -> {
            Object now = call.callRealMethod();
            if (Thread.currentThread().getName().startsWith(RACER) && !paused.get()) {
                paused.set(true);
                try {
                    bothRead.await(400, TimeUnit.MILLISECONDS);
                } catch (BrokenBarrierException | TimeoutException ignored) {
                    // 상대가 회원 잠금에서 기다리는 중이면 그냥 진행한다.
                }
            }
            return now;
        }).when(clock).now();

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        Callable<Boolean> attempt = () -> {
            try {
                auth.login(email, code);
                return true;
            } catch (RuntimeException e) {
                errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        };
        AtomicInteger number = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2, task -> new Thread(task, RACER + number.incrementAndGet()));
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(attempt, attempt), 30, TimeUnit.SECONDS);
            long succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) succeeded++;
            }
            assertThat(succeeded).as("실패 사유: %s", errors).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}

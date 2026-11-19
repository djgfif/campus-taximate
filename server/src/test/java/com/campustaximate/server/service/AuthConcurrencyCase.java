package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.*;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.repository.MemberRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

abstract class AuthConcurrencyCase {
    @Autowired AuthService auth;
    @Autowired MemberRepository members;
    @Autowired PlatformTransactionManager manager;
    @MockBean MailService mail;
    private String email() { return "auth-" + UUID.randomUUID() + "@daejin.ac.kr"; }
    private List<String> race(int count, Supplier<String> work) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try { return work.get(); } catch (ApiException e) { return e.getCode(); }
            }));
            start.countDown();
            var results = new ArrayList<String>();
            for (var future : futures) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void firstRegistrationAndResendAreSerialized() throws Exception {
        String email = email();
        var results = race(6, () -> { auth.requestCode(email); return "OK"; });
        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).allMatch(s -> s.equals("OK") || s.equals("TOO_SOON"));
        assertThat(members.findByEmail(email)).isPresent();
    }
    @Test void oneTimeCodeCanOnlyBeConsumedOnce() throws Exception {
        String email = email(); String code = auth.requestCode(email);
        var results = race(6, () -> { auth.login(email, code); return "OK"; });
        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).allMatch(s -> s.equals("OK") || s.equals("INVALID_CODE"));
        assertThat(members.findByEmail(email).orElseThrow().getLoginCode()).isNull();
    }
    @Test void codeFailureCounterCannotLoseConcurrentUpdates() throws Exception {
        String email = email(); auth.requestCode(email);
        assertThat(race(8, () -> { auth.login(email, "invalid"); return "OK"; })).containsOnly("INVALID_CODE");
        var member = members.findByEmail(email).orElseThrow();
        assertThat(member.getLoginCodeFailures()).isEqualTo(5);
        assertThat(member.getLoginFailuresToday()).isEqualTo(5);
        assertThat(member.getLoginCode()).isNull();
    }
    @Test void passwordFailuresReachDailyLimitAndSurviveOuterRollback() throws Exception {
        String email = email(); long id = auth.login(email, auth.requestCode(email));
        auth.setPassword(id, "correct-password");
        assertThat(race(24, () -> { auth.signIn(email, "wrong-password"); return "OK"; }))
                .allMatch(s -> s.equals("INVALID_PASSWORD") || s.equals("LOGIN_LOCKED"));
        assertThat(members.findById(id).orElseThrow().getLoginFailuresToday()).isEqualTo(20);
        String other = email(); auth.requestCode(other);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            try { auth.signIn(other, "wrong"); } catch (ApiException expected) { }
            throw new IllegalStateException("rollback caller");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(members.findByEmail(other).orElseThrow().getLoginFailuresToday()).isEqualTo(1);
    }

    @Test void staleProfileWriteCannotRestoreConsumedCodeOrEraseFailures() {
        String email = email(); String code = auth.requestCode(email);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var stale = members.findByEmail(email).orElseThrow();
            long id = auth.login(email, code); // independent transaction consumes the code
            try { auth.signIn(email, "wrong"); } catch (ApiException expected) { }
            stale.setDepartment("updated profile");
        });
        var saved = members.findByEmail(email).orElseThrow();
        assertThat(saved.getLoginCode()).isNull();
        assertThat(saved.getLoginFailuresToday()).isEqualTo(1);
        assertThat(saved.getDepartment()).isEqualTo("updated profile");
    }
}

package com.campustaximate.server.service;

import com.campustaximate.server.MysqlContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 회원 행 잠금(SELECT ... FOR UPDATE)을 실제 MySQL InnoDB로 확인한다. Docker가 없으면 건너뛴다. */
@SpringBootTest(properties = {"app.tmap.app-key="})
@ActiveProfiles({"demo", "mysql"})
@Testcontainers(disabledWithoutDocker = true)
class AuthConcurrencyMysqlTest extends AuthConcurrencyCase {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MysqlContainer.register(registry);
    }
}

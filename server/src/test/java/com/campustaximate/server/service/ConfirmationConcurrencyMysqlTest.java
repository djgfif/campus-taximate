package com.campustaximate.server.service;

import com.campustaximate.server.MysqlContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** E-03을 실제 MySQL(InnoDB)에서 돌린다. 테스트가 MySQL 컨테이너를 띄우며, Docker가 없으면 건너뛴다. */
@SpringBootTest(properties = {"app.tmap.app-key="})
@ActiveProfiles({"demo", "mysql"})
@Testcontainers(disabledWithoutDocker = true)
class ConfirmationConcurrencyMysqlTest extends ConfirmationConcurrencyCase {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MysqlContainer.register(registry);
    }
}

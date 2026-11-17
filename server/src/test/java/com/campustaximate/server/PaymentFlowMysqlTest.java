package com.campustaximate.server;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 결제 흐름 전체를 실제 MySQL에서 다시 돌린다. demo,mysql 설정이라 Hibernate 자동 생성이 아니라
 * 실제 Flyway 마이그레이션으로 만든 테이블에서 돈다. Docker가 없으면 건너뛴다.
 */
@SpringBootTest(properties = {"app.upload-dir=target/test-uploads", "app.tmap.app-key="})
@ActiveProfiles({"demo", "mysql"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PaymentFlowMysqlTest extends PaymentFlowTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MysqlContainer.register(registry);
    }
}

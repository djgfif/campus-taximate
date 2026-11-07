package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 운영 설정(Flyway 적용, 엔티티와 스키마 검증)으로 앱이 뜨는지 본다.
 * MYSQL_TEST_URL이 있으면 실제 MySQL에서, 없으면 H2 MySQL 모드에서 돈다. CI는 실제 MySQL을 쓴다.
 */
@SpringBootTest(properties = {
        "app.account-key=test-account-key", "app.kakao.rest-key=test", "spring.mail.host=localhost",
        "app.demo.enabled=false", "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
})
class MigrationSchemaTest {

    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("MYSQL_TEST_URL");
        registry.add("spring.datasource.url",
                () -> url != null ? url : "jdbc:h2:mem:migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> url != null ? "taximate" : "sa");
        registry.add("spring.datasource.password", () -> url != null ? "taximate" : "");
    }

    @Test
    void migrationMatchesEntities() {
        Integer applied = new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied).isPositive();
    }
}

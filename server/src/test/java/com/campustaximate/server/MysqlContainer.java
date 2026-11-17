package com.campustaximate.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * 실제 MySQL 테스트용 일회용 DB. CI·docker-compose와 같은 mysql:8.4.3을 띄운다.
 * 한 테스트 실행(JVM) 안에서 하나만 띄워 여러 테스트 클래스가 함께 쓰고, 실행이 끝나면 Testcontainers가 지운다.
 * 각 스프링 컨텍스트는 demo,mysql 설정이라 시작할 때 Flyway로 비우고 실제 마이그레이션으로 다시 만든다.
 *
 * 쓰는 법: 클래스에 {@code @Testcontainers(disabledWithoutDocker = true)}와 {@code @ActiveProfiles({"demo", "mysql"})}를 달고,
 * {@code @DynamicPropertySource static void db(DynamicPropertyRegistry r) { MysqlContainer.register(r); }}를 둔다.
 * Docker가 없으면 그 테스트는 건너뛴다.
 */
public final class MysqlContainer {

    private static MySQLContainer<?> mysql;

    private MysqlContainer() {
    }

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    private static synchronized MySQLContainer<?> started() {
        if (mysql == null) {
            mysql = new MySQLContainer<>("mysql:8.4.3")
                    .withDatabaseName("taximate")
                    .withUsername("taximate")
                    .withPassword("taximate")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
            mysql.start();
        }
        return mysql;
    }

    public static String jdbcUrl() {
        return started().getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false";
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MysqlContainer::jdbcUrl);
        registry.add("spring.datasource.username", () -> started().getUsername());
        registry.add("spring.datasource.password", () -> started().getPassword());
    }
}

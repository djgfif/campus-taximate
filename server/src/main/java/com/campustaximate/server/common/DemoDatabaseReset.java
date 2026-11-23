package com.campustaximate.server.common;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시연은 시계를 앞으로 돌리므로 실행할 때마다 빈 DB에서 시작한다. 메모리 DB와 같은 동작을 MySQL에서도 맞춘다.
 * 운영 DB를 지우지 않도록 시연 모드이면서, 주소가 이 컴퓨터이거나 시연 전용 DB라고 명시했을 때만 비운다
 * (도커 시연은 DB 주소가 mysql이라 app.demo.reset-database=true로 명시한다).
 */
@Slf4j
@Configuration
public class DemoDatabaseReset {

    @Bean
    FlywayMigrationStrategy migrationStrategy(@Value("${app.demo.enabled}") boolean demo,
                                              @Value("${app.demo.reset-database:false}") boolean demoDatabase,
                                              @Value("${spring.datasource.url}") String url) {
        return flyway -> {
            if (wipes(demo, demoDatabase, url)) {
                log.info("시연 모드라 DB를 비우고 다시 만듭니다.");
                flyway.clean();
            }
            flyway.migrate();
        };
    }

    static boolean wipes(boolean demo, boolean demoDatabase, String url) {
        return demo && (demoDatabase || local(url));
    }

    static boolean local(String url) {
        if (url.startsWith("jdbc:h2:mem:")) {
            return true;
        }
        try {
            String host = URI.create(url.substring("jdbc:".length())).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

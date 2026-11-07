package com.campustaximate.server.common;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시연은 시계를 앞으로 돌리므로 실행할 때마다 빈 DB에서 시작한다. 메모리 DB와 같은 동작을 MySQL에서도 맞춘다.
 * 운영 DB를 지우지 않도록 시연 모드이고 주소가 이 컴퓨터일 때만 비운다.
 */
@Slf4j
@Configuration
public class DemoDatabaseReset {

    @Bean
    FlywayMigrationStrategy migrationStrategy(@Value("${app.demo.enabled}") boolean demo,
                                              @Value("${spring.datasource.url}") String url) {
        return flyway -> {
            if (demo && local(url)) {
                log.info("시연 모드라 DB를 비우고 다시 만듭니다.");
                flyway.clean();
            }
            flyway.migrate();
        };
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

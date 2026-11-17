package com.campustaximate.server.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** MYSQL_TESTS=true 일 때만 실행한다. 회원 행 잠금(SELECT ... FOR UPDATE)을 실제 MySQL InnoDB로 확인한다. */
@SpringBootTest(properties = {"app.tmap.app-key="})
@ActiveProfiles({"demo", "mysql"})
@EnabledIfEnvironmentVariable(named = "MYSQL_TESTS", matches = "true")
class AuthConcurrencyMysqlTest extends AuthConcurrencyCase { }

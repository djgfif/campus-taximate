package com.campustaximate.server.service;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** Shares the existing opt-in local MySQL concurrency test path. */
@SpringBootTest(properties = "app.tmap.app-key=")
@ActiveProfiles({"demo", "mysql"})
@EnabledIfEnvironmentVariable(named = "MYSQL_TESTS", matches = "true")
class AuthConcurrencyMysqlTest extends AuthConcurrencyCase { }

package com.campustaximate.server.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import com.campustaximate.matching.*;import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;import com.campustaximate.server.payment.PaymentGateway;
import java.time.Instant;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.jdbc.core.JdbcTemplate;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * E-03을 실제 MySQL(InnoDB)에서 돌린다. scripts/local-mysql.ps1로 3307 포트 MySQL을 띄우고
 * MYSQL_TESTS=true 일 때만 실행한다. 시연용 DB를 비우고 시작한다.
 */
@SpringBootTest(properties={"app.tmap.app-key="})
@ActiveProfiles({"demo","mysql"})
@EnabledIfEnvironmentVariable(named="MYSQL_TESTS",matches="true")
class ConfirmationConcurrencyMysqlTest extends ConfirmationConcurrencyCase { }

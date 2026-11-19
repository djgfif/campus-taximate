package com.campustaximate.server.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import com.campustaximate.matching.*;import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;import com.campustaximate.server.payment.PaymentGateway;
import java.time.Instant;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.jdbc.core.JdbcTemplate;

/** E-03을 테스트 DB(H2)에서 돌린다. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:confirmation-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1","app.tmap.app-key="})
@ActiveProfiles("demo")
class ConfirmationConcurrencyTest extends ConfirmationConcurrencyCase { }

package com.campustaximate.server;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
/** Run the complete payment/boarding lifecycle suite with real InnoDB transactions in CI. */
@SpringBootTest(properties={"app.upload-dir=target/mysql-test-uploads","app.payment.flow-version=2"})
@ActiveProfiles({"demo","mysql"})
@EnabledIfEnvironmentVariable(named="MYSQL_TESTS",matches="true")
class PaymentFlowMysqlTest extends PaymentFlowTest { }

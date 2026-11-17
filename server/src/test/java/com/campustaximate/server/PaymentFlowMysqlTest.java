package com.campustaximate.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * MYSQL_TESTS=true 일 때만 결제 흐름 전체를 실제 MySQL에서 다시 돌린다.
 * scripts/local-mysql.ps1로 띄운 3307에 taximate_pay DB를 만들고 taximate 사용자에게 권한을 준 뒤 실행한다.
 * 실행 중인 시연 서버의 taximate DB와 섞이지 않게 따로 쓰며, 끝나면 테이블을 지운다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/taximate_pay?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=taximate", "spring.datasource.password=taximate",
        "app.upload-dir=target/test-uploads"})
@ActiveProfiles("demo")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_TESTS", matches = "true")
class PaymentFlowMysqlTest extends PaymentFlowTest { }

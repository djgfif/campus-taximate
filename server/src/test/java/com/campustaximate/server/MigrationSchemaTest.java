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

    @Test
    void v7BackfillsLegacyRequestsAndPreservesPartyColumns() throws Exception {
        String url = "jdbc:h2:mem:migration-v7;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").target("6").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
             var sql = connection.createStatement()) {
            sql.executeUpdate("insert into school(id,lat,lon,email_domain,gate_name,name) values(1,37,127,'test.ac.kr','정문','학교')");
            sql.executeUpdate("insert into hub(id,lat,lon,meeting_point,name) values(1,37,127,'정문 앞','정문')");
            sql.executeUpdate("insert into member(id,school_id,email,login_code_failures,login_failures_today,no_show_count) values(1,1,'a@test.ac.kr',0,0,0)");
            sql.executeUpdate("insert into ride_request(id,member_id,hub_id,arrived,no_show,point_lat,point_lon,shared_seconds,solo_km,solo_seconds,solo_fare,version,point_name,direction,status) "
                    + "values(1,1,1,false,false,37.01,127,0,2.5,600,10000,0,'도착지','FROM_HUB','WAITING')");
            org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").load().migrate();
            try (var row = sql.executeQuery("select r.mode,r.time_basis,r.requested_at,p.walk_seconds,p.anchor_km,p.anchor_seconds,h.active,h.field_checked "
                    + "from ride_request r join ride_request_point p on p.request_id=r.id join hub h on h.id=p.hub_id")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("mode")).isEqualTo("RESERVATION");
                assertThat(row.getString("time_basis")).isEqualTo("DEPART");
                assertThat(row.getTimestamp("requested_at")).isNotNull();
                assertThat(row.getInt("walk_seconds")).isZero();
                assertThat(row.getDouble("anchor_km")).isEqualTo(2.5);
                assertThat(row.getInt("anchor_seconds")).isEqualTo(600);
                assertThat(row.getBoolean("active")).isTrue();
                assertThat(row.getBoolean("field_checked")).isTrue();
            }
            // 출발 시각·승차 지점은 기존 컬럼을 그대로 사용한다.
            try (var columns = sql.executeQuery("select hub_id,departure_at from party")) {
                assertThat(columns.getMetaData().getColumnCount()).isEqualTo(2);
            }
        }
    }

    @Test
    void v8AddsPaymentTablesAndKeepsLegacyPartyFlow() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[]{"payment_order","payment_operation","settlement_share","fare_objection","payout_ledger"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isNotNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from party where payment_flow_version is null",Long.class)).isZero();
    }

    @Test void v9PreservesMigrationValuesAndMarksOriginalUnknown() throws Exception {
        String url="jdbc:h2:mem:migration_v9_"+java.util.UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").target("8").load().migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()) {
            sql.executeUpdate("insert into school(id,lat,lon,email_domain,gate_name,name) values(1,37,127,'v9.ac.kr','정문','학교')");
            sql.executeUpdate("insert into hub(id,lat,lon,meeting_point,name) values(1,37,127,'앞','정문')");
            sql.executeUpdate("insert into member(id,school_id,email,login_code_failures,login_failures_today,no_show_count) values(1,1,'a@v9.ac.kr',0,0,0)");
            sql.executeUpdate("insert into party(id,hub_id,payer_id,departure_at,expected_total,version,direction,status,payment_flow_version) values(1,1,1,current_timestamp,16800,0,'FROM_HUB','READY',2)");
            sql.executeUpdate("insert into ride_request(id,member_id,hub_id,party_id,arrived,no_show,point_lat,point_lon,shared_seconds,solo_km,solo_seconds,solo_fare,version,point_name,direction,status,expected_share,requested_at,mode,time_basis,early_arrival_minutes) values(1,1,1,1,false,false,37,127,600,2,600,6000,0,'도착지','FROM_HUB','MATCHED',3200,current_timestamp,'RESERVATION','DEPART',10)");
            sql.executeUpdate("insert into payment_order(id,version,order_id,party_id,member_id,request_id,purpose,status,amount,approved_amount,refunded_amount,refund_target,deadline_at,created_at) values(1,0,'v9_order',1,1,1,'PREPAY','REQUIRED',3680,0,0,0,current_timestamp,current_timestamp)");
            org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var rows=sql.executeQuery("select confirmed_share,confirmed_prepay,migrated_unknown from party_member_term")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(3200);assertThat(rows.getLong(2)).isEqualTo(3680);assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.next()).isFalse();
            }
            try(var rows=sql.executeQuery("select group_version from party where id=1")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);}
            sql.executeUpdate("update party set status='RIDE_UNCONFIRMED' where id=1");
        }
    }
}

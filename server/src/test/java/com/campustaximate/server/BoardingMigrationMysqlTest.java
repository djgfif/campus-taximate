package com.campustaximate.server;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import java.sql.*;
/** CI supplies a dedicated schema, never the application's or concurrency tests' schema. */
@EnabledIfEnvironmentVariable(named="MYSQL_TESTS",matches="true")
class BoardingMigrationMysqlTest {
    @Test void v20UpgradeRestoresExcludedRequestReasonsOnMysql() throws Exception {
        String url=System.getenv("MYSQL_MIGRATION_TEST_URL");
        assertThat(url).contains("/taximate_migration?");
        Flyway.configure().dataSource(url,"taximate","taximate").cleanDisabled(false).load().clean();
        RequestExitMigrationTest.verifyUpgrade(url,"taximate","taximate");
    }
    @Test void v23BackfillsDeadlinesAndPayoutsOnMysql() throws Exception {
        String url = System.getenv("MYSQL_MIGRATION_TEST_URL");
        assertThat(url).contains("/taximate_migration?");
        Flyway.configure().dataSource(url, "taximate", "taximate").cleanDisabled(false).load().clean();
        MigrationSchemaTest.verifyV23Upgrade(url, "taximate", "taximate");
    }

    @Test void freshAndV15UpgradeOnRealMysql() throws Exception {
        String url=System.getenv("MYSQL_MIGRATION_TEST_URL");
        assertThat(url).as("MYSQL_MIGRATION_TEST_URL must name the isolated migration schema").isNotBlank();
        assertThat(url).contains("/taximate_migration?");
        Flyway latest=Flyway.configure().dataSource(url,"taximate","taximate").cleanDisabled(false).load();
        latest.clean(); latest.migrate();
        try(var c=DriverManager.getConnection(url,"taximate","taximate");var sql=c.createStatement()) {
            try(var rows=sql.executeQuery("select boarding_code,boarding_generation,boarding_deadline_at,boarding_closed_at from party where 1=0")) {
                assertThat(rows.getMetaData().getColumnCount()).isEqualTo(4);
            }
            try(var rows=sql.executeQuery("select boarding_confirmed_at from ride_request where 1=0")){assertThat(rows.getMetaData().getColumnCount()).isEqualTo(1);}
        }
        latest.clean(); Flyway.configure().dataSource(url,"taximate","taximate").target("15").load().migrate();
        try(var c=DriverManager.getConnection(url,"taximate","taximate");var sql=c.createStatement()) {
            sql.executeUpdate("insert into school(id,lat,lon,email_domain,gate_name,name) values(1,37,127,'upgrade.ac.kr','gate','school')");
            sql.executeUpdate("insert into hub(id,lat,lon,meeting_point,name) values(1,37,127,'gate','hub')");
            sql.executeUpdate("insert into member(id,school_id,email,login_code_failures,login_failures_today,no_show_count) values(1,1,'a@upgrade.ac.kr',0,0,0)");
            sql.executeUpdate("insert into party(id,hub_id,payer_id,departure_at,expected_total,version,direction,status,payment_flow_version) values(1,1,1,current_timestamp,16800,0,'FROM_HUB','READY',2)");
            latest.migrate();
            try(var rows=sql.executeQuery("select boarding_code,boarding_generation,started_at,status from party where id=1")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isNull();assertThat(rows.getInt(2)).isZero();assertThat(rows.getTimestamp(3)).isNull();assertThat(rows.getString(4)).isEqualTo("READY");
            }
            sql.executeUpdate("insert into boarding_attempt(party_id,member_id,generation,failures) values(1,1,1,5)");
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,()->sql.executeUpdate("insert into boarding_attempt(party_id,member_id,generation,failures) values(1,1,1,0)"));
        }
    }
}

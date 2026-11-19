package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class RequestExitMigrationTest {
    @Test void v20UpgradeRestoresExcludedRequestReasonsOnH2() throws Exception {
        verifyUpgrade("jdbc:h2:mem:exit_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** Only a new H2 database or the isolated MySQL migration schema may be passed here. */
    static void verifyUpgrade(String url, String user, String password) throws Exception {
        Flyway.configure().dataSource(url,user,password).target("20").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password);var sql=c.createStatement()) {
            sql.executeUpdate("insert into school(id,lat,lon,email_domain,gate_name,name) values(1,37,127,'exit.ac.kr','gate','school')");
            sql.executeUpdate("insert into hub(id,lat,lon,meeting_point,name) values(1,37,127,'gate','hub')");
            sql.executeUpdate("insert into member(id,school_id,email,login_code_failures,login_failures_today,no_show_count) values(1,1,'a@exit.ac.kr',0,0,0)");
            sql.executeUpdate("insert into party(id,hub_id,payer_id,departure_at,expected_total,version,direction,status,payment_flow_version) values(1,1,1,current_timestamp,16800,0,'FROM_HUB','READY',2)");
            String[] reasons={"BOARDING_UNCONFIRMED","PAYER_NO_SHOW","PREPAY_UNPAID","LATE_CANCEL","FREE_CANCEL","UNKNOWN","BOARDING_UNCONFIRMED","BOARDING_UNCONFIRMED"};
            for(int i=1;i<=reasons.length;i++) {
                sql.executeUpdate("insert into ride_request(id,member_id,hub_id,arrived,no_show,point_lat,point_lon,shared_seconds,solo_km,solo_seconds,solo_fare,version,point_name,direction,status,end_reason,party_id) values("
                        +i+",1,1,false,false,37,127,600,2,600,6000,0,'stop','FROM_HUB','"+(i==8?"MATCHED":"CANCELED")+"',"+(i==7?"'NO_SHOW'":"null")+","+(i==8?"1":"null")+")");
                sql.executeUpdate("insert into party_member_term(party_id,request_id,member_id,confirmed_share,confirmed_prepay,migrated_unknown,refund_held,exited_at,exit_reason) values(1,"
                        +i+",1,3200,3680,false,true,current_timestamp,'"+reasons[i-1]+"')");
            }
            Flyway.configure().dataSource(url,user,password).load().migrate();
            String[] expected={"BOARDING_UNCONFIRMED","BOARDING_UNCONFIRMED","PREPAY_UNPAID","LATE_CANCEL","FREE_CANCEL",null,"NO_SHOW",null};
            try(var rows=sql.executeQuery("select end_reason from ride_request order by id")) {
                for(String reason:expected) {assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(reason);}
                assertThat(rows.next()).isFalse();
            }
            for(String reason:new String[]{"BOARDING_UNCONFIRMED","PREPAY_UNPAID","FREE_CANCEL","CHANGE_EXIT_FREE","LATE_CANCEL","ROUTE_VERIFICATION_EXPIRED"})
                assertThat(sql.executeUpdate("update ride_request set end_reason='"+reason+"' where id=6")).isEqualTo(1);
        }
    }
}

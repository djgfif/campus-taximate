package com.campustaximate.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:boarding-points;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class BoardingPointControllerTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired HubRepository hubs;
    @Autowired MemberRepository members;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockHttpSession session;
    @BeforeEach void prepare() {
        session = new MockHttpSession();
        var member = members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow();
        session.setAttribute(LoginTokenFilter.SESSION_KEY, member.getId());
    }
    @Test void returnsOnlyCheckedActivePointsWithinTenMinutesNearestFirstAtMostThree() throws Exception {
        var school = members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow().getSchool();
        for (int i=4; i>=1; i--) hubs.save(new Hub("근처 " + i,"만나는 곳",37+i*0.0005,127,Set.of(school)));
        var inactive = hubs.save(new Hub("비활성","만나는 곳",37,127,Set.of(school)));
        var unchecked = hubs.save(new Hub("미확인","만나는 곳",37,127,Set.of(school)));
        hubs.save(new Hub("걷기 초과","먼 곳",37.02,127,Set.of(school)));
        jdbc.update("update hub set active=false where id=?",inactive.getId());
        jdbc.update("update hub set field_checked=false where id=?",unchecked.getId());
        long before = hubs.count();
        var rows = json.readTree(mvc.perform(get("/api/hubs/nearby").session(session).param("lat","37").param("lon","127"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("근처 1");
        assertThat(rows.get(1).get("name").asText()).isEqualTo("근처 2");
        assertThat(rows.get(2).get("name").asText()).isEqualTo("근처 3");
        for (var row:rows) { assertThat(row.get("walkSeconds").asInt()).isLessThanOrEqualTo(600); assertThat(row.get("walkEstimated").asBoolean()).isTrue(); }
        assertThat(hubs.count()).isEqualTo(before);
    }
    @Test void rejectsInvalidCoordinatesAndRequiresLogin() throws Exception {
        mvc.perform(get("/api/hubs/nearby").param("lat","37").param("lon","127")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/hubs/nearby").session(session).param("lat","91").param("lon","127")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/hubs/nearby").session(session).param("lat","NaN").param("lon","127")).andExpect(status().isBadRequest());
    }
}

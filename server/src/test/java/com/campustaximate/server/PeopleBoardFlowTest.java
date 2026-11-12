package com.campustaximate.server;

import static org.assertj.core.api.Assertions.*;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 지금 모집 중 게시판, 게시판 합류, 공개 프로필, 칭찬. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:people-board;MODE=MySQL;DB_CLOSE_DELAY=-1","app.rate-limit.enabled=false","app.kakao.rest-key=","app.toss.secret-key=","app.mail.enabled=false","app.upload-dir=target/people-board-uploads"})
@ActiveProfiles("demo")
@Import(PeopleBoardFlowTest.FixedTime.class)
class PeopleBoardFlowTest {
    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary AppClock fixedPeopleClock() {
            return new AppClock() {
                final AtomicReference<Instant> time = new AtomicReference<>(NOW);
                @Override public Instant now() { return time.get(); }
                @Override public void moveTo(Instant target) { time.set(target); }
            };
        }
    }
    @Autowired AppClock clock;
    @Autowired DemoService demo;
    @Autowired BoardService board;
    @Autowired ProfileService profiles;
    @Autowired PartyService views;
    @Autowired RideRequestRepository requests;
    @Autowired PartyRepository parties;
    @Autowired MemberRepository members;
    @Autowired SettlementRepository settlements;
    @Autowired TransferRequestRepository transfers;
    @Autowired JdbcTemplate jdbc;
    long me;

    @BeforeEach void reset() {
        PaymentTestSupport.clear(jdbc);
        jdbc.update("delete from ride_review");
        jdbc.update("delete from party_message");
        transfers.deleteAll(); settlements.deleteAll(); requests.deleteAll(); parties.deleteAll();
        jdbc.update("delete from member_restriction"); jdbc.update("update member set no_show_count=0");
        clock.moveTo(NOW);
        me = members.findByEmail("demo-m4@daejin.ac.kr").orElseThrow().getId();
    }

    @Test void boardShowsSameGenderOpenPartyWithoutDropoffsAndLetsMeJoin() {
        demo.openBoardParty(me);
        var seen = board.of(me);
        assertThat(seen.parties()).hasSize(1);
        var card = seen.parties().getFirst();
        assertThat(card.seats()).isPositive();
        assertThat(card.people()).allSatisfy(p -> assertThat(p.profile()).contains("학번"));
        // 다른 성별에게는 보이지 않는다.
        long other = members.findByEmail("demo-f4@daejin.ac.kr").orElseThrow().getId();
        assertThat(board.of(other).parties()).isEmpty();

        // 실제 시계처럼 분 중간(초가 있는 시각)에 합류해도 된다.
        clock.moveTo(clock.now().plusSeconds(17));
        var join = board.join(me, card.id(), new BoardService.JoinInput("시장 입구", 37.8330, 127.1460, "검은 백팩"));
        assertThat(join.getState()).isEqualTo("PENDING");
        assertThat(requests.findByActiveMemberId(me)).get().extracting(RideRequest::getStatus).isEqualTo(RequestStatus.JOINING);
        // 합류 중인 일행은 다른 사람의 게시판에서 빠진다.
        assertThat(board.of(me).parties()).isEmpty();
    }

    @Test void failedJoinDoesNotLeaveAWaitingRequest() {
        demo.openBoardParty(me);
        var card = board.of(me).parties().getFirst();
        clock.moveTo(card.joinUntil().plusSeconds(60));
        assertThatThrownBy(() -> board.join(me, card.id(), new BoardService.JoinInput("시장 입구", 37.8330, 127.1460, null)))
                .isInstanceOf(ApiException.class);
        assertThat(requests.findByActiveMemberId(me)).isEmpty();
    }

    @Test void profileLineRespectsPrivacyAndPraiseNeedsARide() {
        profiles.update(me, new ProfileService.ProfileView("컴퓨터공학과", 22, true));
        var member = members.findById(me).orElseThrow();
        assertThat(profiles.publicLine(member)).isEqualTo("컴퓨터공학과 · 22학번");
        profiles.update(me, new ProfileService.ProfileView("컴퓨터공학과", 22, false));
        assertThat(profiles.publicLine(members.findById(me).orElseThrow())).isEmpty();

        demo.openBoardParty(me);
        var card = board.of(me).parties().getFirst();
        long host = requests.findByPartyOrderByRideOrder(parties.findById(card.id()).orElseThrow()).getFirst().getMember().getId();
        long mate = requests.findByPartyOrderByRideOrder(parties.findById(card.id()).orElseThrow()).getLast().getMember().getId();
        var people = profiles.people(host, card.id());
        assertThat(people).hasSize(2).anyMatch(ProfileService.PersonView::me);
        assertThatThrownBy(() -> profiles.people(me, card.id())).isInstanceOf(ApiException.class);
        // 같이 타기 전에는 칭찬할 수 없다.
        assertThatThrownBy(() -> profiles.praise(host, card.id(), mate, List.of("KIND"))).isInstanceOf(ApiException.class);
        var party = parties.findById(card.id()).orElseThrow();
        party.setStatus(PartyStatus.COMPLETED);
        parties.save(party);
        var after = profiles.praise(host, card.id(), mate, List.of("KIND", "ON_TIME"));
        profiles.praise(host, card.id(), mate, List.of("KIND"));
        var target = after.stream().filter(p -> p.memberId() == mate).findFirst().orElseThrow();
        assertThat(target.praises()).isEqualTo(2);
        assertThat(target.myTags()).containsExactly("KIND", "ON_TIME");
        assertThatThrownBy(() -> profiles.praise(host, card.id(), host, List.of("KIND"))).isInstanceOf(ApiException.class);
    }
}

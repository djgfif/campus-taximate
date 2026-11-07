package com.campustaximate.server.service;

import com.campustaximate.matching.Gender;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.School;
import com.campustaximate.server.repository.HubRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.SchoolRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 학교와 거점 초기 데이터. 좌표는 시연용 샘플이라 실제 거점 좌표로 바꿔야 한다. */
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    public static final int DEMO_MEMBERS_PER_GENDER = 4;
    /** 시연 계정 비밀번호. 시연 프로필에서만 만들어진다. */
    public static final String DEMO_PASSWORD = "demo1234";
    private static final List<String> FEMALE_NICKNAMES = List.of("하늘", "바다", "구름", "별");
    private static final List<String> MALE_NICKNAMES = List.of("산", "강", "숲", "들");

    public record Dropoff(String name, double lat, double lon) {
    }

    public static final List<Dropoff> SAMPLE_DROPOFFS = List.of(
            new Dropoff("사거리 편의점 앞", 37.8430, 127.1500),
            new Dropoff("시장 입구", 37.8330, 127.1460),
            new Dropoff("주공아파트 앞 정류장", 37.8240, 127.1425),
            new Dropoff("역전 버스 정류장", 37.8150, 127.1390));

    /** 시드가 만든 시연 계정인지 메일로 판단한다. 가입으로는 같은 메일을 만들 수 없다. */
    public static boolean isSeededDemo(String email) {
        return email.matches("demo-[fm][1-" + DEMO_MEMBERS_PER_GENDER + "]@daejin\\.ac\\.kr");
    }

    private final SchoolRepository schools;
    private final HubRepository hubs;
    private final MemberRepository members;

    @Value("${app.demo.enabled}")
    private boolean demo;

    @Override
    public void run(ApplicationArguments args) {
        if (schools.count() > 0) {
            return;
        }
        School school = schools.save(new School("대진대학교", "daejin.ac.kr", "학교 정문", 37.875, 127.155));
        hubs.save(new Hub("학교 정문", "정문 앞 버스 정류장", 37.875, 127.155, Set.of(school)));
        if (demo) {
            for (int i = 1; i <= DEMO_MEMBERS_PER_GENDER; i++) {
                seedMember(school, "demo-f" + i, FEMALE_NICKNAMES.get(i - 1), Gender.FEMALE);
                seedMember(school, "demo-m" + i, MALE_NICKNAMES.get(i - 1), Gender.MALE);
            }
        }
    }

    private void seedMember(School school, String id, String nickname, Gender gender) {
        Member member = new Member(school, id + "@daejin.ac.kr");
        member.setNickname(nickname);
        member.setGender(gender);
        member.setPasswordHash(PasswordHasher.hash(DEMO_PASSWORD));
        members.save(member);
    }
}

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

    /** 시연 계정의 비밀번호. demo 프로필에서만 만든다. */
    public static final String DEMO_PASSWORD = "demo1234";
    public static final int DEMO_MEMBERS_PER_GENDER = 4;

    public record Dropoff(String name, double lat, double lon) {
    }

    public static final List<Dropoff> SAMPLE_DROPOFFS = List.of(
            new Dropoff("샘플 지점 1 (10km)", 37.785, 127.105),
            new Dropoff("샘플 지점 2 (15km)", 37.745, 127.075),
            new Dropoff("샘플 지점 3 (20km)", 37.700, 127.050),
            new Dropoff("샘플 지점 4 (25km)", 37.660, 127.030));

    /** 시드가 만든 시연 계정인지 메일로 판단한다. 가입으로는 같은 메일을 만들 수 없다. */
    public static boolean isSeededDemo(String email) {
        return email.matches("demo-[fm][1-" + DEMO_MEMBERS_PER_GENDER + "]@daejin\\.ac\\.kr");
    }

    private final SchoolRepository schools;
    private final HubRepository hubs;
    private final MemberRepository members;
    private final AuthService auth;

    @Value("${app.demo.enabled}")
    private boolean demo;

    @Override
    public void run(ApplicationArguments args) {
        if (schools.count() > 0) {
            return;
        }
        School school = schools.save(new School("대진대학교", "daejin.ac.kr"));
        hubs.save(new Hub("샘플 거점 (학교 정문)", 37.875, 127.155, Set.of(school)));
        if (demo) {
            for (int i = 1; i <= DEMO_MEMBERS_PER_GENDER; i++) {
                seedMember(school, "demo-f" + i, "여학생" + i, Gender.FEMALE);
                seedMember(school, "demo-m" + i, "남학생" + i, Gender.MALE);
            }
        }
    }

    private void seedMember(School school, String id, String nickname, Gender gender) {
        Member member = new Member(school, id + "@daejin.ac.kr", auth.encode(DEMO_PASSWORD), nickname, gender);
        member.setVerified(true);
        members.save(member);
    }
}

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
    /** 시연 계정 이름. 실제 학생처럼 보이도록 흔한 이름을 쓴다. */
    private static final List<String> FEMALE_NICKNAMES = List.of("하은", "서연", "지민", "유나");
    private static final List<String> MALE_NICKNAMES = List.of("준호", "도윤", "민재", "현우");
    /** 시연 계정의 학과·학번. 실제 학생처럼 보이게 서로 다르게 둔다. */
    private static final List<String> DEPARTMENTS = List.of("컴퓨터공학과", "경영학과", "간호학과", "디자인학과", "전자공학과", "영어영문학과", "체육학과", "행정학과");
    private static final List<Integer> YEARS = List.of(22, 23, 21, 24, 23, 22, 25, 21);
    /** 예전 시드 이름. 이미 만들어진 시연 계정도 새 이름으로 바꾼다. */
    private static final List<String> OLD_NICKNAMES = List.of("하늘", "바다", "구름", "별", "산", "강", "숲", "들");

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
    private final org.springframework.core.env.Environment environment;

    @Value("${app.demo.enabled}")
    private boolean demo;

    @Override
    public void run(ApplicationArguments args) {
        demo = demo && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo"));
        if (schools.count() > 0) {
            if(demo) seedDemo(schools.findAll().getFirst());
            return;
        }
        School school = schools.save(new School("대진대학교", "daejin.ac.kr", "학교 정문", 37.875, 127.155));
        hubs.save(new Hub("학교 정문", "정문 앞 버스 정류장", 37.875, 127.155, Set.of(school)));
        if (demo) {
            seedDemo(school);
        }
    }

    private void seedDemo(School school) {
        seedAdmin(school);
        for(int i=1;i<=DEMO_MEMBERS_PER_GENDER;i++) {
            seedMember(school,"demo-f"+i,FEMALE_NICKNAMES.get(i-1),Gender.FEMALE,2*(i-1));
            seedMember(school,"demo-m"+i,MALE_NICKNAMES.get(i-1),Gender.MALE,2*(i-1)+1);
        }
    }

    private void seedAdmin(School school) {
        Member admin=members.findByEmail("demo-admin@daejin.ac.kr").orElseGet(()->new Member(school,"demo-admin@daejin.ac.kr"));
        admin.setNickname("시연 관리자");admin.setGender(Gender.FEMALE);admin.setAdmin(true);
        if(admin.getPasswordHash()==null) admin.setPasswordHash(PasswordHasher.hash(DEMO_PASSWORD));
        members.save(admin);
    }

    private void seedMember(School school, String id, String nickname, Gender gender, int index) {
        var existing = members.findByEmail(id + "@daejin.ac.kr");
        if (existing.isPresent()) {
            Member old = existing.get();
            if (old.getNickname() == null || OLD_NICKNAMES.contains(old.getNickname())) old.setNickname(nickname);
            if (old.getDepartment() == null) { old.setDepartment(DEPARTMENTS.get(index)); old.setAdmissionYear(YEARS.get(index)); }
            members.save(old);
            return;
        }
        Member member = new Member(school, id + "@daejin.ac.kr");
        member.setNickname(nickname);
        member.setGender(gender);
        member.setDepartment(DEPARTMENTS.get(index));
        member.setAdmissionYear(YEARS.get(index));
        member.setPasswordHash(PasswordHasher.hash(DEMO_PASSWORD));
        members.save(member);
    }
}

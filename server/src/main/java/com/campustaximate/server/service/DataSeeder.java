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

/** 학교와 거점 초기 데이터는 설정(app.schools)에서 만든다. 시연 계정은 첫 학교의 메일 도메인으로 만든다. */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
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

    /** 시드가 만든 시연 계정인지 메일로 판단한다. 시연 계정은 시연 프로필에서만 만들어진다. */
    public static boolean isSeededDemo(String email) {
        return email.matches("demo-[fm][1-" + DEMO_MEMBERS_PER_GENDER + "]@[^@]+");
    }

    private final SchoolRepository schools;
    private final HubRepository hubs;
    private final MemberRepository members;
    private final PaymentMethodService paymentMethods;
    private final org.springframework.core.env.Environment environment;
    private final com.campustaximate.server.common.SchoolSeedConfiguration seeds;
    private final com.campustaximate.server.common.DemoDropoffConfiguration demoConfig;

    @Value("${app.demo.enabled}")
    private boolean demo;

    @Override
    public void run(ApplicationArguments args) {
        demo = demo && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo"));
        if (schools.count() == 0) {
            if (seeds.getSchools().isEmpty()) {
                log.warn("app.schools 설정이 비어 있어 학교를 만들지 않았습니다. 학교가 없으면 아무도 가입할 수 없습니다.");
                return;
            }
            for (var seed : seeds.getSchools()) {
                School school = schools.save(new School(seed.getName(), seed.getEmailDomain().trim().toLowerCase(java.util.Locale.ROOT),
                        seed.getGateName(), seed.getLat(), seed.getLon()));
                hubs.save(new Hub(seed.getGateName(), seed.spot(), seed.getLat(), seed.getLon(), Set.of(school)));
            }
        }
        if (demo) seedDemo(demoSchool());
    }

    /** 시연 계정이 속한 학교. 가장 먼저 만든 학교다. */
    private School demoSchool() {
        return schools.findAll().stream().min(java.util.Comparator.comparing(School::getId)).orElseThrow();
    }

    /** 시연 계정 이름(demo-f1 등)의 메일 주소. */
    public String demoEmail(String account) {
        return account + "@" + demoSchool().getEmailDomain();
    }

    private void seedDemo(School school) {
        seedDemoHubs(school);
        seedAdmin(school);
        for(int i=1;i<=DEMO_MEMBERS_PER_GENDER;i++) {
            seedMember(school,"demo-f"+i,FEMALE_NICKNAMES.get(i-1),Gender.FEMALE,2*(i-1));
            seedMember(school,"demo-m"+i,MALE_NICKNAMES.get(i-1),Gender.MALE,2*(i-1)+1);
        }
    }

    private void seedDemoHubs(School school) {
        Set<String> existing = hubs.findBySchool(school.getId()).stream()
                .map(Hub::getName)
                .collect(java.util.stream.Collectors.toSet());
        for (var hub : demoConfig.getHubs()) {
            if (!existing.contains(hub.name())) {
                String meetingPoint = hub.meetingPoint() == null || hub.meetingPoint().isBlank() ? hub.name() : hub.meetingPoint();
                hubs.save(new Hub(hub.name(), meetingPoint, hub.lat(), hub.lon(), Set.of(school)));
                existing.add(hub.name());
            }
        }
    }

    private void seedAdmin(School school) {
        String email = "demo-admin@" + school.getEmailDomain();
        Member admin=members.findByEmail(email).orElseGet(()->new Member(school,email));
        admin.setNickname("시연 관리자");admin.setGender(Gender.FEMALE);admin.setAdmin(true);
        if(admin.getPasswordHash()==null) admin.setPasswordHash(PasswordHasher.hash(DEMO_PASSWORD));
        paymentMethods.seedDemo(members.save(admin).getId());
    }

    private void seedMember(School school, String id, String nickname, Gender gender, int index) {
        String email = id + "@" + school.getEmailDomain();
        var existing = members.findByEmail(email);
        if (existing.isPresent()) {
            Member old = existing.get();
            if (old.getNickname() == null || OLD_NICKNAMES.contains(old.getNickname())) old.setNickname(nickname);
            if (old.getDepartment() == null) { old.setDepartment(DEPARTMENTS.get(index)); old.setAdmissionYear(YEARS.get(index)); }
            paymentMethods.seedDemo(members.save(old).getId());
            return;
        }
        Member member = new Member(school, email);
        member.setNickname(nickname);
        member.setGender(gender);
        member.setDepartment(DEPARTMENTS.get(index));
        member.setAdmissionYear(YEARS.get(index));
        member.setPasswordHash(PasswordHasher.hash(DEMO_PASSWORD));
        paymentMethods.seedDemo(members.save(member).getId());
    }
}

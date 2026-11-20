package com.campustaximate.server.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 처음 띄울 때 만들 학교 목록. 학교마다 메일 도메인이 하나라서, 로그인한 메일로 어느 학교 학생인지 정해진다.
 * 코드에는 특정 학교를 두지 않는다. 운영은 설정 파일이나 환경 변수로, 시연과 테스트는 각 프로필 설정으로 넣는다.
 * 학교가 이미 있는 데이터베이스에는 다시 만들지 않는다.
 */
@Configuration @ConfigurationProperties(prefix = "app") @Validated @Getter @Setter
public class SchoolSeedConfiguration {
    @Valid private List<SchoolSeed> schools = new ArrayList<>();

    @Getter @Setter
    public static class SchoolSeed {
        /** 화면에 보이는 학교 이름. */
        @NotBlank private String name;
        /** 학생 메일 도메인. 예: example.ac.kr */
        @NotBlank private String emailDomain;
        /** 등교 택시가 내려 주고 하교 택시가 출발하는 곳. */
        @NotBlank private String gateName;
        /** 그곳에서 정확히 만나는 자리. 비우면 gateName과 같다. */
        private String gateSpot;
        private double lat;
        private double lon;

        public String spot() {
            return gateSpot == null || gateSpot.isBlank() ? gateName : gateSpot;
        }
    }
}

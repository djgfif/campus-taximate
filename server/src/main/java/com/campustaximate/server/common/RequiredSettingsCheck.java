package com.campustaximate.server.common;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 운영 실행에 꼭 필요한 설정이 없으면 서버를 띄우지 않는다. 쓰는 순간 실패하거나 조용히 안 되는 일을 막는다. */
@Component
public class RequiredSettingsCheck implements InitializingBean {

    @Value("${app.demo.enabled}")
    private boolean demo;

    @Value("${app.account-key}")
    private String accountKey;

    @Value("${app.kakao.rest-key}")
    private String kakaoKey;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Override
    public void afterPropertiesSet() {
        List<String> missing = missing(demo, accountKey, kakaoKey, mailHost);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("운영 실행에 필요한 설정이 없습니다: " + String.join(", ", missing));
        }
    }

    static List<String> missing(boolean demo, String accountKey, String kakaoKey, String mailHost) {
        List<String> missing = new ArrayList<>();
        if (demo) {
            return missing;
        }
        if (accountKey == null || accountKey.isBlank()) {
            missing.add("ACCOUNT_KEY");
        }
        if (kakaoKey == null || kakaoKey.isBlank()) {
            missing.add("KAKAO_REST_KEY");
        }
        if (mailHost == null || mailHost.isBlank()) {
            missing.add("MAIL_HOST");
        }
        return missing;
    }
}

package com.campustaximate.server.common;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 시연 계정이 내릴 곳으로 쓰는 시연 지점(app.demo.dropoffs). 시연 학교 주변 실제 장소를 학교 가까운 순서로 넣는다.
 * 시연 학교를 바꾸면 이 목록도 그 학교 주변으로 바꾼다. 코드에는 특정 지역 좌표를 두지 않는다.
 */
@Configuration @ConfigurationProperties(prefix = "app.demo") @Getter @Setter
public class DemoDropoffConfiguration {
    private List<Dropoff> dropoffs = new ArrayList<>();

    public record Dropoff(String name, double lat, double lon) {
    }

    /** index번째 시연 지점. 목록보다 큰 번호는 처음부터 돌아가며 고른다. */
    public Dropoff at(int index) {
        if (dropoffs.isEmpty()) throw ApiException.conflict("DEMO_NO_DROPOFFS", "시연 지점(app.demo.dropoffs)이 설정되지 않았어요.");
        return dropoffs.get(index % dropoffs.size());
    }
}

package com.campustaximate.server.service;

import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.RideRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 승차 시각(하교는 출발 시각) 10분 전에 한 번 알린다. 앱을 닫고 있어도 시간을 놓치지 않게 한다. */
@Service
@RequiredArgsConstructor
public class ReminderService {

    static final Duration BEFORE = Duration.ofMinutes(10);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
            .withZone(ZoneId.of("Asia/Seoul"));

    private final RideRequestRepository requests;
    private final NotificationService notifications;

    @Transactional
    public void remind(Instant now) {
        for (RideRequest rider : requests.findDueReminders(now, now.plus(BEFORE))) {
            rider.setRemindedAt(now);
            String place = rider.getHub() != null ? rider.getHub().getMeetingPoint() : rider.getPointName();
            notifications.toMember(rider.getMember(), "[캠퍼스 택시메이트] 곧 승차 시각",
                    TIME.format(rider.startAt()) + " " + place + ". 도착하면 앱에서 도착했어요를 눌러 주세요.");
        }
    }
}

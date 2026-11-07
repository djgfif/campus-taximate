package com.campustaximate.server.service;

import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승차 10분 전과 미송금 24시간 뒤에 각각 한 번 안내한다. 메일은 기존 outbox가 재시도한다. */
@Service
@RequiredArgsConstructor
public class ReminderService {

    static final Duration BEFORE = Duration.ofMinutes(10);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
            .withZone(ZoneId.of("Asia/Seoul"));

    private final RideRequestRepository requests;
    private final NotificationService notifications;
    private final TransferRequestRepository transfers;
    private final MailService mail;

    @Transactional
    public void remind(Instant now) {
        Instant before = now.minus(Duration.ofHours(24));
        for (var transfer : transfers.findDueUnsentReminders(before)) {
            // 표시와 outbox를 같은 트랜잭션에 저장해 재시도해도 중복 등록하지 않는다.
            if (transfers.claimReminder(transfer.getId(), now, before) == 1) {
                mail.send(transfer.getSender().getEmail(), "[캠퍼스 택시메이트] 미송금 안내",
                        "정산 요청 후 24시간이 지났어요. 송금한 뒤 앱에서 보냈어요를 눌러 주세요.");
            }
        }
        for (RideRequest rider : requests.findDueReminders(now, now.plus(BEFORE))) {
            rider.setRemindedAt(now);
            String place = rider.getHub() != null ? rider.getHub().getMeetingPoint() : rider.getPointName();
            notifications.toMember(rider.getMember(), "[캠퍼스 택시메이트] 곧 승차 시각",
                    TIME.format(rider.startAt()) + " " + place + ". 도착하면 앱에서 도착했어요를 눌러 주세요.");
        }
    }
}

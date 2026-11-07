package com.campustaximate.server.service;

import com.campustaximate.server.domain.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 사람에게 보내는 알림은 메일과 웹 푸시로 함께 간다. 메일은 재시도, 푸시는 허용한 기기에만 간다. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String PREFIX = "[캠퍼스 택시메이트] ";

    private final MailService mail;
    private final PushService push;

    public void toMember(Member member, String subject, String text) {
        mail.send(member.getEmail(), subject, text);
        push.send(member.getId(), subject.replace(PREFIX, ""), text, "/");
    }
}

package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.PushSubscription;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PushSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 웹 푸시. 앱을 닫아도 확정, 취소, 승차 안내를 받는다. 키가 없으면 꺼진다(시연은 실행마다 새 키를 만든다).
 * 서버가 사용자가 준 주소로 요청을 보내므로 알려진 푸시 서비스 주소만 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            ".notify.windows.com", ".push.apple.com");

    private final PushSubscriptionRepository subscriptions;
    private final MemberRepository members;
    private final AppClock clock;
    private final ObjectMapper json;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "web-push");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${app.push.public-key:}")
    private String publicKey;

    @Value("${app.push.private-key:}")
    private String privateKey;

    @Value("${app.push.subject}")
    private String subject;

    @Value("${app.demo.enabled}")
    private boolean demo;

    private nl.martijndwars.webpush.PushService sender;

    @PostConstruct
    void start() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if ((publicKey == null || publicKey.isBlank()) && demo) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(new ECGenParameterSpec("prime256v1"));
            KeyPair pair = generator.generateKeyPair();
            publicKey = org.bouncycastle.util.encoders.Base64.toBase64String(Utils.encode((ECPublicKey) pair.getPublic()))
                    .replace('+', '-').replace('/', '_').replace("=", "");
            privateKey = org.bouncycastle.util.encoders.Base64.toBase64String(Utils.encode((ECPrivateKey) pair.getPrivate()))
                    .replace('+', '-').replace('/', '_').replace("=", "");
        }
        if (enabled()) {
            sender = new nl.martijndwars.webpush.PushService(publicKey, privateKey, subject);
        }
    }

    public boolean enabled() {
        return publicKey != null && !publicKey.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    public String publicKey() {
        return enabled() ? publicKey : null;
    }

    @Transactional
    public void subscribe(long memberId, String endpoint, String p256dh, String auth) {
        if (!enabled()) {
            throw ApiException.conflict("PUSH_OFF", "이 서버는 푸시 알림을 쓰지 않습니다.");
        }
        if (!allowed(endpoint)) {
            throw ApiException.badRequest("PUSH_ENDPOINT", "지원하지 않는 알림 주소입니다.");
        }
        Member member = members.getReferenceById(memberId);
        PushSubscription row = subscriptions.findByEndpoint(endpoint)
                .orElseGet(() -> new PushSubscription(member, endpoint, p256dh, auth, clock.now()));
        row.setMember(member);
        row.setP256dh(p256dh);
        row.setAuth(auth);
        subscriptions.save(row);
    }

    @Transactional
    public void unsubscribe(long memberId, String endpoint) {
        subscriptions.findByEndpoint(endpoint)
                .filter(row -> row.getMember().getId() == memberId)
                .ifPresent(subscriptions::delete);
    }

    static boolean allowed(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && PUSH_HOSTS.stream().anyMatch(h -> h.startsWith(".") ? host.endsWith(h) : host.equals(h));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 커밋 뒤 별도 스레드에서 보낸다. 실패해도 업무에는 영향이 없고 메일이 함께 간다. */
    public void send(long memberId, String title, String body, String url) {
        if (!enabled()) {
            return;
        }
        Runnable task = () -> deliver(memberId, title, body, url);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    worker.execute(task);
                }
            });
        } else {
            worker.execute(task);
        }
    }

    private void deliver(long memberId, String title, String body, String url) {
        for (PushSubscription row : subscriptions.findByMemberId(memberId)) {
            try {
                byte[] payload = json.writeValueAsBytes(Map.of("title", title, "body", body, "url", url));
                var response = sender.send(new Notification(row.getEndpoint(), row.getP256dh(), row.getAuth(), payload));
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    subscriptions.deleteByEndpoint(row.getEndpoint());
                } else if (status >= 300) {
                    log.warn("푸시 실패. member={} status={}", memberId, status);
                }
            } catch (Exception e) {
                log.warn("푸시 실패. member={}", memberId, e);
            }
        }
    }
}

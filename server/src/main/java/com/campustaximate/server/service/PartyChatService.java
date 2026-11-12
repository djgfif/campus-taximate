package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 일행 채팅. 현재 일행원만 읽고 쓴다. 새 메시지·읽음 변화는 ChangeBus의 chat 신호로 바로 알린다.
 * 시스템 알림(확정, 선결제, 도착 등)은 보낸 사람 없이 남기고, 일행 잠금을 잡지 않는다(다른 잠금 순서와 엇갈리지 않게).
 */
@Service
@RequiredArgsConstructor
public class PartyChatService {
    private static final Duration CHAT_LIMIT = Duration.ofHours(24);
    private static final Duration KEEP = Duration.ofDays(7);
    private static final int MAX_PER_MINUTE = 10;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PartyMessageRepository messages;
    private final PartyChatReadRepository reads;
    private final AppClock clock;
    private final ChangeBus changes;
    private final PushService push;
    private final MemberRepository members;

    @Value("${app.upload-dir}")
    private String uploadDir;

    /** memberId는 시스템 알림이면 null. unread는 보낸 사람을 뺀 현재 일행원 중 아직 안 읽은 사람 수. clientId는 내 메시지에만. */
    public record MessageView(long id, Long memberId, String nickname, String text, Instant createdAt, boolean mine,
                              String kind, String imageUrl, int unread, String clientId) {
        public MessageView(long id, long memberId, String nickname, String text, Instant createdAt, boolean mine) {
            this(id, memberId, nickname, text, createdAt, mine, PartyMessage.TEXT, null, 0, null);
        }
    }
    public record ChatView(List<MessageView> messages, boolean closed, Instant closesAt, boolean hasOlder, long lastReadId) {
        public ChatView(List<MessageView> messages, boolean closed, Instant closesAt) {
            this(messages, closed, closesAt, false, 0);
        }
    }
    public record ImageFile(Path path, String contentType) { }

    /** 일행 잠금을 첫 읽기로 잡아 취소/정산 완료와 권한 판정을 직렬화한다. before가 있으면 그보다 이전 50개. */
    @Transactional
    public ChatView read(long actor, long partyId, Long after) {
        return read(actor, partyId, after, null);
    }

    @Transactional
    public ChatView read(long actor, long partyId, Long after, Long before) {
        Party party = lock(partyId);
        List<RideRequest> riders = currentMembers(party);
        requireMember(actor, riders);
        if ((after != null && after < 0) || (before != null && before < 0)) {
            throw ApiException.badRequest("INVALID_AFTER", "메시지 번호를 확인해 주세요.");
        }
        List<PartyMessage> rows;
        if (after != null) {
            rows = messages.findTop50ByPartyIdAndIdGreaterThanOrderByIdAsc(partyId, after);
        } else {
            rows = new ArrayList<>(before == null ? messages.findTop50ByPartyIdOrderByIdDesc(partyId)
                    : messages.findTop50ByPartyIdAndIdLessThanOrderByIdDesc(partyId, before));
            Collections.reverse(rows);
        }
        Instant end = endsAt(party);
        // 스케줄러가 다음에 돌기 전에도 보관 기간이 지난 내용은 내려주지 않는다.
        if (expired(party)) return new ChatView(List.of(), closed(party), end, false, 0);
        Map<Long, Long> readBy = readPositions(partyId, riders);
        List<MessageView> views = rows.stream().map(m -> view(m, actor, partyId, readBy)).toList();
        boolean hasOlder = after == null && !rows.isEmpty() && messages.existsByPartyIdAndIdLessThan(partyId, rows.getFirst().getId());
        return new ChatView(views, closed(party), end, hasOlder, readBy.getOrDefault(actor, 0L));
    }

    @Transactional
    public MessageView send(long actor, long partyId, String text) {
        return send(actor, partyId, text, null);
    }

    @Transactional
    public MessageView send(long actor, long partyId, String text, String clientId) {
        Party party = lock(partyId);
        List<RideRequest> riders = currentMembers(party);
        RideRequest mine = writable(actor, party, riders);
        String key = clientKey(clientId);
        if (key != null) {
            var existing = messages.findByPartyIdAndMemberIdAndClientId(partyId, actor, key);
            if (existing.isPresent()) return view(existing.get(), actor, partyId, readPositions(partyId, riders));
        }
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw ApiException.badRequest("INVALID_MESSAGE", "메시지는 1~200자로 입력해 주세요.");
        }
        requireRate(actor, partyId);
        PartyMessage saved = messages.save(PartyMessage.text(party, mine.getMember(), trimmed, now(), key));
        advanceRead(partyId, actor, saved.getId());
        String preview = trimmed.substring(0, trimmed.offsetByCodePoints(0, Math.min(40, trimmed.codePointCount(0, trimmed.length()))));
        announce(partyId, riders, actor, preview);
        return view(saved, actor, partyId, readPositions(partyId, riders));
    }

    /** JPG·PNG·WEBP 한 장(5MB 이하). 파일 앞부분으로 형식을 확인한다. */
    @Transactional
    public MessageView sendImage(long actor, long partyId, MultipartFile file, String clientId) {
        Party party = lock(partyId);
        List<RideRequest> riders = currentMembers(party);
        RideRequest mine = writable(actor, party, riders);
        String key = clientKey(clientId);
        if (key != null) {
            var existing = messages.findByPartyIdAndMemberIdAndClientId(partyId, actor, key);
            if (existing.isPresent()) return view(existing.get(), actor, partyId, readPositions(partyId, riders));
        }
        if (file == null || file.isEmpty()) throw ApiException.badRequest("IMAGE_REQUIRED", "보낼 사진을 골라 주세요.");
        String type = imageType(file);
        requireRate(actor, partyId);
        String path = store(partyId, file);
        PartyMessage saved = messages.save(PartyMessage.image(party, mine.getMember(), path, type, now(), key));
        advanceRead(partyId, actor, saved.getId());
        announce(partyId, riders, actor, "사진을 보냈어요");
        return view(saved, actor, partyId, readPositions(partyId, riders));
    }

    @Transactional(readOnly = true)
    public ImageFile image(long actor, long partyId, long messageId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.forbidden("현재 일행원만 채팅할 수 있어요."));
        requireMember(actor, currentMembers(party));
        PartyMessage m = messages.findById(messageId).filter(x -> x.getParty().getId().equals(partyId) && x.getImagePath() != null)
                .orElseThrow(() -> ApiException.notFound("사진이 없어요."));
        if (expired(party) || !Files.exists(Path.of(m.getImagePath()))) throw ApiException.notFound("보관 기간이 지나 사진이 삭제됐어요.");
        return new ImageFile(Path.of(m.getImagePath()), m.getImageType());
    }

    /** 읽은 위치는 앞으로만 간다. 바뀌면 다른 일행원 화면의 안 읽은 수를 갱신시킨다. */
    @Transactional
    public long markRead(long actor, long partyId, long lastId) {
        Party party = lock(partyId);
        List<RideRequest> riders = currentMembers(party);
        requireMember(actor, riders);
        if (lastId < 0) throw ApiException.badRequest("INVALID_AFTER", "메시지 번호를 확인해 주세요.");
        PartyMessage latest = messages.findTop50ByPartyIdOrderByIdDesc(partyId).stream().findFirst().orElse(null);
        long capped = latest == null ? 0 : Math.min(lastId, latest.getId());
        PartyChatRead row = reads.findByPartyIdAndMemberId(partyId, actor).orElseGet(() -> new PartyChatRead(partyId, actor, 0));
        if (capped > row.getLastReadId()) {
            row.setLastReadId(capped);
            reads.save(row);
            changes.chatChanged(partyId, memberIds(riders));
        }
        return row.getLastReadId();
    }

    /** 내가 안 읽은 메시지 수(내 메시지 제외, 시스템 알림 포함). 일행 목록 배지에 쓴다. */
    @Transactional(readOnly = true)
    public long unread(long actor, long partyId) {
        long last = reads.findByPartyIdAndMemberId(partyId, actor).map(PartyChatRead::getLastReadId).orElse(0L);
        return messages.countUnread(partyId, actor, last);
    }

    /** 확정·선결제·도착 같은 일을 채팅에 남긴다. 실패해도 본래 처리를 막지 않는다. */
    public void system(Party party, String text) {
        if (party == null || party.getId() == null || text == null || text.isBlank()) return;
        try {
            String trimmed = text.strip();
            messages.save(PartyMessage.system(party, trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed, now()));
            changes.chatChanged(party.getId(), requests.findMemberIdsByPartyId(party.getId()));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PartyChatService.class).warn("채팅 알림을 남기지 못했어요 party={} reason={}", party.getId(), e.getMessage());
        }
    }

    /** template의 %s 자리에 그 회원의 닉네임을 넣어 시스템 알림을 남긴다. */
    public void system(Party party, long memberId, String template) {
        String name = members.findById(memberId).map(Member::getNickname).orElse("일행원");
        system(party, String.format(template, name));
    }

    @Transactional
    public void purgeMessages() {
        for (long partyId : messages.findPartyIdsWithMessages()) {
            Party party = lock(partyId);
            if (expired(party)) {
                messages.findImagePaths(partyId).forEach(p -> {
                    try { Files.deleteIfExists(Path.of(p)); } catch (IOException ignored) { /* 다음 회차에 다시 시도하지 않는다: 행은 지운다 */ }
                });
                messages.deleteForParty(partyId);
                reads.deleteForParty(partyId);
            }
        }
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    private void announce(long partyId, List<RideRequest> riders, long actor, String preview) {
        List<Long> ids = memberIds(riders);
        changes.chatChanged(partyId, ids);
        ids.stream().filter(id -> id != actor).forEach(id -> {
            changes.memberChanged(id);
            push.send(id, "[캠퍼스 택시메이트] 일행 메시지", preview, "/");
        });
    }

    /** 보낸 사람은 그 앞의 메시지를 모두 읽은 것으로 본다. */
    private void advanceRead(long partyId, long actor, long id) {
        PartyChatRead row = reads.findByPartyIdAndMemberId(partyId, actor).orElseGet(() -> new PartyChatRead(partyId, actor, 0));
        if (id > row.getLastReadId()) { row.setLastReadId(id); reads.save(row); }
    }

    private void requireRate(long actor, long partyId) {
        if (messages.countByPartyIdAndMemberIdAndCreatedAtAfter(partyId, actor, clock.now().minusSeconds(60)) >= MAX_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_TOO_FAST", "메시지를 너무 빨리 보내고 있어요. 잠시 뒤 다시 보내 주세요.");
        }
    }

    private static String clientKey(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        String key = clientId.strip();
        if (key.length() > 64 || !key.matches("[A-Za-z0-9_-]+")) throw ApiException.badRequest("INVALID_CLIENT_ID", "메시지 번호를 확인해 주세요.");
        return key;
    }

    private String imageType(MultipartFile file) {
        if (file.getContentType() == null || !IMAGE_TYPES.contains(file.getContentType())) {
            throw ApiException.badRequest("IMAGE_TYPE", "JPG, PNG, WEBP 사진만 보낼 수 있어요.");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            boolean jpeg = head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            boolean png = head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            boolean webp = head.length >= 12 && new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                    && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP");
            if (jpeg) return "image/jpeg";
            if (png) return "image/png";
            if (webp) return "image/webp";
            throw ApiException.badRequest("IMAGE_TYPE", "JPG, PNG, WEBP 사진만 보낼 수 있어요.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String store(long partyId, MultipartFile file) {
        try {
            Path dir = Path.of(uploadDir, "chat", String.valueOf(partyId));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString());
            file.transferTo(target.toAbsolutePath());
            return target.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Party lock(long partyId) {
        return parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.forbidden("현재 일행원만 채팅할 수 있어요."));
    }

    private List<RideRequest> currentMembers(Party party) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .filter(r -> !r.isNoShow() && (r.getStatus() == RequestStatus.MATCHED || r.getStatus() == RequestStatus.COMPLETED))
                .toList();
    }

    private static List<Long> memberIds(List<RideRequest> riders) {
        return riders.stream().map(r -> r.getMember().getId()).distinct().toList();
    }

    private void requireMember(long actor, List<RideRequest> riders) {
        if (riders.stream().noneMatch(r -> r.getMember().getId() == actor)) {
            throw ApiException.forbidden("현재 일행원만 채팅할 수 있어요.");
        }
    }

    private RideRequest writable(long actor, Party party, List<RideRequest> riders) {
        RideRequest mine = riders.stream().filter(r -> r.getMember().getId() == actor).findFirst()
                .orElseThrow(() -> ApiException.forbidden("현재 일행원만 채팅할 수 있어요."));
        if (closed(party)) throw ApiException.conflict("CHAT_CLOSED", "일행 채팅은 읽기만 가능해요.");
        return mine;
    }

    private Map<Long, Long> readPositions(long partyId, List<RideRequest> riders) {
        Map<Long, Long> positions = reads.findByPartyId(partyId).stream()
                .collect(Collectors.toMap(PartyChatRead::getMemberId, PartyChatRead::getLastReadId, Math::max));
        Map<Long, Long> result = new HashMap<>();
        memberIds(riders).forEach(id -> result.put(id, positions.getOrDefault(id, 0L)));
        return result;
    }

    private Instant endsAt(Party party) {
        Instant deadline = party.getDepartureAt().plus(CHAT_LIMIT);
        Instant settled = party.getSettledAt();
        return settled != null && settled.isBefore(deadline) ? settled : deadline;
    }

    private boolean closed(Party party) {
        return party.getStatus() == PartyStatus.COMPLETED || List.of(PartyStatus.CANCELED, PartyStatus.DISSOLVED, PartyStatus.NOT_OPERATED, PartyStatus.FARE_MISSING).contains(party.getStatus())
                || !clock.now().isBefore(endsAt(party));
    }

    private boolean expired(Party party) {
        return !clock.now().isBefore(endsAt(party).plus(KEEP));
    }

    private MessageView view(PartyMessage message, long actor, long partyId, Map<Long, Long> readBy) {
        Member sender = message.getMember();
        boolean mine = sender != null && sender.getId() == actor;
        int unread = message.system() ? 0 : (int) readBy.entrySet().stream()
                .filter(e -> sender == null || !e.getKey().equals(sender.getId()))
                .filter(e -> e.getValue() < message.getId()).count();
        String imageUrl = message.getImagePath() == null ? null : "/api/parties/" + partyId + "/messages/" + message.getId() + "/image";
        return new MessageView(message.getId(), sender == null ? null : sender.getId(), sender == null ? null : sender.getNickname(),
                message.getText(), message.getCreatedAt(), mine, message.getKind(), imageUrl, unread, mine ? message.getClientId() : null);
    }
}

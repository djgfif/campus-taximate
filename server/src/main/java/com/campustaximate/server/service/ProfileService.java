package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideReview;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.RideReviewRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같이 탈 사람을 믿을 수 있게 하는 정보. 학과·학번(본인이 공개할 때만), 탄 횟수, 노쇼 여부, 받은 칭찬.
 * 칭찬만 받고 나쁜 평가는 받지 않는다. 문제는 신고로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {
    /** 칭찬 종류. 화면 문구는 프런트가 정한다. */
    public static final Set<String> TAGS = Set.of("ON_TIME", "KIND", "QUIET", "CLEAR_PAY");
    private static final Set<PartyStatus> REVIEWABLE = Set.of(PartyStatus.AWAITING_FARE, PartyStatus.OBJECTION_WINDOW,
            PartyStatus.REVIEW_PENDING, PartyStatus.SETTLED, PartyStatus.COMPLETED);

    public record ProfileView(String department, Integer admissionYear, boolean showProfile) { }
    public record PersonView(long memberId, String nickname, String profile, long rides, boolean noShowFree,
                             int praises, List<String> topTags, List<String> myTags, boolean me) { }

    private final MemberRepository members;
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final RideReviewRepository reviews;
    private final AppClock clock;

    /** 일행에게 보이는 한 줄: "컴퓨터공학과 · 22학번". 공개하지 않으면 빈 문자열이다. */
    public String publicLine(Member member) {
        if (!member.isShowProfile()) return "";
        String department = member.getDepartment() == null ? "" : member.getDepartment().strip();
        String year = member.getAdmissionYear() == null ? "" : String.format("%02d학번", member.getAdmissionYear());
        if (department.isEmpty()) return year;
        return year.isEmpty() ? department : department + " · " + year;
    }

    @Transactional(readOnly = true)
    public ProfileView profile(long memberId) {
        Member m = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        return new ProfileView(m.getDepartment(), m.getAdmissionYear(), m.isShowProfile());
    }

    @Transactional
    public ProfileView update(long memberId, ProfileView input) {
        Member m = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        String department = input.department() == null ? null : input.department().strip();
        if (department != null && department.length() > 40) throw ApiException.badRequest("INVALID_DEPARTMENT", "학과는 40자 이하로 적어 주세요.");
        Integer year = input.admissionYear();
        if (year != null && (year < 0 || year > 99)) throw ApiException.badRequest("INVALID_YEAR", "학번은 두 자리로 골라 주세요.");
        m.setDepartment(department == null || department.isEmpty() ? null : department);
        m.setAdmissionYear(year);
        m.setShowProfile(input.showProfile());
        return profile(memberId);
    }

    /** 일행 화면의 "같이 타는 사람". 같은 일행원만 볼 수 있다. */
    @Transactional(readOnly = true)
    public List<PersonView> people(long viewerId, long partyId) {
        List<Long> ids = requests.findMemberIdsByPartyId(partyId);
        if (!ids.contains(viewerId)) throw ApiException.forbidden("일행원만 볼 수 있어요.");
        Set<String> mine = reviews.findByPartyIdAndReviewerId(partyId, viewerId).stream()
                .map(r -> r.getTargetId() + ":" + r.getTag()).collect(Collectors.toSet());
        return ids.stream().distinct().map(id -> {
            Member m = members.findById(id).orElseThrow();
            List<RideReview> got = reviews.findByTargetId(id);
            Map<String, Long> byTag = got.stream().collect(Collectors.groupingBy(RideReview::getTag, LinkedHashMap::new, Collectors.counting()));
            List<String> top = byTag.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .limit(2).map(Map.Entry::getKey).toList();
            List<String> myTags = TAGS.stream().filter(tag -> mine.contains(id + ":" + tag)).sorted().toList();
            return new PersonView(id, m.getNickname(), publicLine(m), requests.countByMemberIdAndStatusAndNoShowFalse(id, RequestStatus.COMPLETED),
                    m.getNoShowCount() == 0, got.size(), top, myTags, id == viewerId);
        }).toList();
    }

    /** 운행이 끝난 일행에서 같이 탄 사람에게 칭찬을 남긴다. 같은 칭찬은 한 번만 남는다. */
    @Transactional
    public List<PersonView> praise(long viewerId, long partyId, long targetId, List<String> tags) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        List<Long> ids = requests.findMemberIdsByPartyId(partyId);
        if (!ids.contains(viewerId) || !ids.contains(targetId)) throw ApiException.forbidden("같이 탄 사람에게만 남길 수 있어요.");
        if (viewerId == targetId) throw ApiException.badRequest("SELF_REVIEW", "나에게는 남길 수 없어요.");
        if (!REVIEWABLE.contains(party.getStatus())) throw ApiException.conflict("REVIEW_TOO_EARLY", "같이 탄 뒤에 남길 수 있어요.");
        if (tags == null || tags.isEmpty() || !TAGS.containsAll(tags)) throw ApiException.badRequest("INVALID_TAG", "칭찬을 골라 주세요.");
        for (String tag : Set.copyOf(tags)) {
            if (reviews.existsByPartyIdAndReviewerIdAndTargetIdAndTag(partyId, viewerId, targetId, tag)) continue;
            RideReview review = new RideReview();
            review.setPartyId(partyId);
            review.setReviewerId(viewerId);
            review.setTargetId(targetId);
            review.setTag(tag);
            review.setCreatedAt(clock.now());
            reviews.save(review);
        }
        return people(viewerId, partyId);
    }
}

package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.domain.PartyJoin;
import com.campustaximate.server.service.BoardService;
import com.campustaximate.server.service.ProfileService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 사람이 보이는 기능: 지금 모집 중 게시판, 같이 타는 사람의 신뢰 정보, 칭찬, 내 공개 프로필. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PeopleController {
    private final BoardService board;
    private final ProfileService profiles;

    public record JoinBody(@NotBlank @Size(max = 100) String pointName, @NotNull @DecimalMin("-90") @DecimalMax("90") Double pointLat,
                           @NotNull @DecimalMin("-180") @DecimalMax("180") Double pointLon, @Size(max = 100) String memo) { }
    public record PraiseBody(@NotNull Long targetId, @NotNull @Size(min = 1, max = 4) List<String> tags) { }
    public record ProfileBody(@Size(max = 40) String department, Integer admissionYear, Boolean showProfile) { }

    private long actor(HttpSession session) {
        Object id = session.getAttribute(LoginTokenFilter.SESSION_KEY);
        if (!(id instanceof Long)) throw ApiException.unauthorized();
        return (Long) id;
    }

    @GetMapping("/board")
    public BoardService.Board board(HttpSession session) { return board.of(actor(session)); }

    @PostMapping("/board/parties/{id}/join")
    public PartyJoin join(@PathVariable long id, @Valid @RequestBody JoinBody body, HttpSession session) {
        return board.join(actor(session), id, new BoardService.JoinInput(body.pointName(), body.pointLat(), body.pointLon(), body.memo()));
    }

    @GetMapping("/parties/{id}/people")
    public List<ProfileService.PersonView> people(@PathVariable long id, HttpSession session) { return profiles.people(actor(session), id); }

    @PostMapping("/parties/{id}/praises")
    public List<ProfileService.PersonView> praise(@PathVariable long id, @Valid @RequestBody PraiseBody body, HttpSession session) {
        return profiles.praise(actor(session), id, body.targetId(), body.tags());
    }

    @GetMapping("/me/profile")
    public ProfileService.ProfileView profile(HttpSession session) { return profiles.profile(actor(session)); }

    @PutMapping("/me/profile")
    public ProfileService.ProfileView updateProfile(@Valid @RequestBody ProfileBody body, HttpSession session) {
        return profiles.update(actor(session), new ProfileService.ProfileView(body.department(), body.admissionYear(), body.showProfile() == null || body.showProfile()));
    }
}

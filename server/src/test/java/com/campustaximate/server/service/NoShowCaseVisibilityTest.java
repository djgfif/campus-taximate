package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NoShowCaseVisibilityTest {
    @Mock MemberRepository members;
    @Mock PartyMemberTermRepository terms;
    @Mock BoardingCaseRepository cases;
    @Mock BoardingVoteRepository votes;
    @Mock BoardingExplanationRepository explanations;
    @Mock RideRequestRepository requests;
    @InjectMocks NoShowService service;
    private BoardingCase target() {
        var c=new BoardingCase();c.setId(3L);c.setPartyId(11L);c.setRequestId(30L);c.setMemberId(20L);
        when(terms.findByPartyIdAndMemberId(11L,20L)).thenReturn(Optional.of(new PartyMemberTerm()));
        var member=new Member();member.setId(20L);member.setNickname("제외된 일행");member.setEmail("private@example.com");
        when(members.findById(20L)).thenReturn(Optional.of(member));
        return c;
    }
    @Test void peerSeesNicknameButCannotReadAnotherMembersExplanation() {
        var c=target();when(cases.findByPartyIdOrderByRequestId(11L)).thenReturn(List.of(c));
        when(terms.findByPartyIdAndMemberId(11L,10L)).thenReturn(Optional.of(new PartyMemberTerm()));
        var view=service.list(10L,11L).getFirst();
        assertThat(view.nickname()).isEqualTo("제외된 일행");assertThat(view.explanation()).isNull();
        verifyNoInteractions(explanations,requests);
    }
    @Test void subjectKeepsExplanationInPartyListAndMineWithoutActiveRequest() {
        var c=target();var explanation=new BoardingExplanation();explanation.setExplanation("본인 소명 원문");
        when(cases.findByPartyIdOrderByRequestId(11L)).thenReturn(List.of(c));
        when(cases.findByMemberIdOrderByIdDesc(20L)).thenReturn(List.of(c));
        when(explanations.findByCaseId(3L)).thenReturn(Optional.of(explanation));
        assertThat(service.list(20L,11L).getFirst().explanation()).isSameAs(explanation);
        assertThat(service.mine(20L).getFirst()).satisfies(v->{
            assertThat(v.explanation()).isSameAs(explanation);assertThat(v.nickname()).isEqualTo("제외된 일행");
        });
        verifyNoInteractions(requests);
    }
    @Test void outsiderCannotResolveCasesOrNicknames() {
        assertThatThrownBy(()->service.list(99L,11L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(cases,members,explanations);
    }
    @Test void invalidTargetAssociationIsRejectedBeforeMemberLookup() {
        var c=new BoardingCase();c.setId(3L);c.setPartyId(11L);c.setMemberId(99L);
        when(cases.findByPartyIdOrderByRequestId(11L)).thenReturn(List.of(c));
        when(terms.findByPartyIdAndMemberId(11L,10L)).thenReturn(Optional.of(new PartyMemberTerm()));
        assertThatThrownBy(()->service.list(10L,11L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(members,explanations);
    }
}

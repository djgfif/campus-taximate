package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.web.AdminController;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminNoShowEvidenceTest {
    @Mock MemberRepository members;
    @Mock PartyRepository parties;
    @Mock BoardingCaseRepository cases;
    @Mock PartyMessageRepository messages;
    @Mock AppClock clock;
    @Mock PartyMemberTermRepository terms;
    @Mock SettlementRepository settlements;
    @Mock BoardingVoteRepository votes;
    @Mock BoardingExplanationRepository explanations;
    @InjectMocks AdminService service;
    @TempDir Path directory;
    private static final Instant NOW=Instant.parse("2026-11-18T00:00:00Z");
    private Party givenCase() {
        var admin=new Member();admin.setId(1L);admin.setAdmin(true);
        when(members.findById(1L)).thenReturn(Optional.of(admin));
        var c=new BoardingCase();c.setId(3L);c.setPartyId(11L);c.setRequestId(30L);c.setMemberId(20L);
        when(cases.findById(3L)).thenReturn(Optional.of(c));
        var party=new Party();party.setId(11L);party.setDepartureAt(NOW);party.setSettledAt(NOW.plusSeconds(3600));
        when(parties.findById(11L)).thenReturn(Optional.of(party));
        return party;
    }
    private PartyMessage photo(Party party,Path path) {
        var sender=new Member();sender.setId(20L);
        var message=PartyMessage.image(party,sender,path.toString(),"image/png",NOW,"photo");
        ReflectionTestUtils.setField(message,"id",50L);return message;
    }
    private void notFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    @Test void adminImageEndpointStreamsStoredMediaWithNoStoreAndNosniff() throws Exception {
        var party=givenCase();var path=Files.write(directory.resolve("evidence.png"),new byte[]{1,2,3});
        when(messages.findById(50L)).thenReturn(Optional.of(photo(party,path)));when(clock.now()).thenReturn(NOW);
        var mvc=MockMvcBuilders.standaloneSetup(new AdminController(service,mock(PaymentLifecycleService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var session=new MockHttpSession();session.setAttribute("memberId",1L);
        mvc.perform(get("/api/admin/no-shows/3/messages/50/image").session(session))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3}))
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
    }
    @Test void endpointRejectsAnonymousAndNonAdminBeforeEvidenceLookup() throws Exception {
        var member=new Member();member.setId(10L);when(members.findById(10L)).thenReturn(Optional.of(member));
        var mvc=MockMvcBuilders.standaloneSetup(new AdminController(service,mock(PaymentLifecycleService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/admin/no-shows/3/messages/50/image")).andExpect(status().isUnauthorized());
        var session=new MockHttpSession();session.setAttribute("memberId",10L);
        mvc.perform(get("/api/admin/no-shows/3/messages/50/image").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(cases,parties,messages);
    }
    @Test void anotherPartysPhotoCannotBeReadThroughThisCase() throws Exception {
        givenCase();var other=new Party();other.setId(12L);
        when(messages.findById(50L)).thenReturn(Optional.of(photo(other,directory.resolve("other.png"))));
        notFound(()->service.noShowMessageImage(1L,3L,50L));verifyNoInteractions(clock);
    }
    @Test void textMessageCannotBeUsedAsImageEvidence() {
        var party=givenCase();var sender=new Member();sender.setId(20L);
        when(messages.findById(50L)).thenReturn(Optional.of(PartyMessage.text(party,sender,"text",NOW,null)));
        notFound(()->service.noShowMessageImage(1L,3L,50L));verifyNoInteractions(clock);
    }
    @Test void missingFileIsNotFound() {
        var party=givenCase();when(messages.findById(50L)).thenReturn(Optional.of(photo(party,directory.resolve("missing.png"))));
        when(clock.now()).thenReturn(NOW);notFound(()->service.noShowMessageImage(1L,3L,50L));
    }
    @Test void settledRetentionBoundaryAppliesEvenBeforePurge() throws Exception {
        var party=givenCase();var path=Files.write(directory.resolve("evidence.png"),new byte[]{1});
        when(messages.findById(50L)).thenReturn(Optional.of(photo(party,path)));
        Instant expiry=party.getSettledAt().plus(Duration.ofDays(7));
        when(clock.now()).thenReturn(expiry.minusNanos(1));
        assertThat(service.noShowMessageImage(1L,3L,50L).path()).isEqualTo(path);
        when(clock.now()).thenReturn(expiry);notFound(()->service.noShowMessageImage(1L,3L,50L));
    }
    @Test void departurePlusEightDaysIsUpperRetentionBoundary() {
        var party=givenCase();party.setSettledAt(NOW.plus(Duration.ofDays(2)));
        when(messages.findById(50L)).thenReturn(Optional.of(photo(party,directory.resolve("evidence.png"))));
        when(clock.now()).thenReturn(NOW.plus(Duration.ofDays(8)));
        notFound(()->service.noShowMessageImage(1L,3L,50L));
    }
    @Test void detailKeepsAdminExplanationAndSuppliesImageContractWithoutCrossPartyOrSystemLines() {
        var party=givenCase();when(clock.now()).thenReturn(NOW);
        when(terms.findByPartyIdAndRequestId(11L,30L)).thenReturn(Optional.of(new PartyMemberTerm()));
        var explanation=new BoardingExplanation();explanation.setExplanation("관리자에게 제출한 원문");
        when(explanations.findByCaseId(3L)).thenReturn(Optional.of(explanation));
        var image=photo(party,directory.resolve("evidence.png"));
        var text=PartyMessage.text(party,image.getMember(),"text",NOW,null);ReflectionTestUtils.setField(text,"id",51L);
        var other=new Party();other.setId(12L);
        when(messages.findTop50ByPartyIdOrderByIdDesc(11L)).thenReturn(List.of(image,text,
                PartyMessage.system(party,"system",NOW),photo(other,directory.resolve("other.png"))));
        var detail=service.noShowDetail(1L,3L);
        assertThat(detail.explanation()).isSameAs(explanation);assertThat(detail.chat()).hasSize(2);
        assertThat(detail.chat().getFirst()).satisfies(line->{
            assertThat(line.kind()).isEqualTo("IMAGE");
            assertThat(line.imageUrl()).isEqualTo("/api/admin/no-shows/3/messages/50/image");
        });
        assertThat(detail.chat().getLast().kind()).isEqualTo("TEXT");assertThat(detail.chat().getLast().imageUrl()).isNull();
        when(clock.now()).thenReturn(party.getSettledAt().plus(Duration.ofDays(7)));
        assertThat(service.noShowDetail(1L,3L).chat()).isEmpty();
    }
}

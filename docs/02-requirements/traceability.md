# 요구사항 추적표 v2.0 (Requirements Traceability Matrix)

> **목적:** `v2.0 FR(40개) ↔ UC(14개) ↔ 시퀀스 다이어그램(3종) ↔ Taximate 3.0 화면 ↔ 백엔드 구현/전환 상태 ↔ 자동 테스트`를 한 표로 연결한다.
> **구현 상태 표기:** `완료(유지)` = 현행 `main`에 구현·검증 완료 / `전환 필요` = 현행 S43(선결제·적립금)에서 v2.0(결제수단 등록·확인형 후불 1회 결제)으로 전환 시 수정 대상.

---

## 1. 시퀀스 다이어그램 ↔ 유스케이스 ↔ FR 연결

| 시퀀스 다이어그램 | 담당 유스케이스 | 담당 FR | 핵심 백엔드 클래스 |
|---|---|---|---|
| **시퀀스 ① 요청과 자동 매칭** | `UC-02`, `UC-03`, `UC-04` | `FR-R01~R04`, `FR-M01~M05` | `RideRequestService`, `SlotService`, `MatchingRound`, `RouteGroupVerifier` |
| **시퀀스 ② 탑승 인증과 운행** | `UC-05`, `UC-11` | `FR-B01~B06` | `PartyReadinessService`, `BoardingCodeService`, `PartyService`, `NoShowService` |
| **시퀀스 ③ 실제 요금·확인형 1회 결제·정산** | `UC-07`, `UC-08`, `UC-09`, `UC-12`, `UC-13` | `FR-S01~S08`, `FR-O01~O03` | `SettlementService`, `PaymentService`, `PaymentLifecycleService`, `AdminService` |

---

## 2. 전체 요구사항 추적표 (FR 40개)

| FR | 등급 | UC | Taximate 3.0 경로 | 구현 상태 | 핵심 구현 클래스 | 기준선 검증 테스트 |
|---|---|---|---|---|---|---|
| `FR-A01` | P0 | UC-01 | `/register` | 완료(유지) | `AuthService`, `PasswordHasher` | `AuthConcurrencyTest`, `FlowTest` |
| `FR-A02` | P0 | UC-01 | `/login` | 완료(유지) | `AuthService`, `LoginTokenService` | `ApiSecurityTest`, `FlowTest` |
| `FR-A03` | P1 | UC-01 | `/reset-password` | 완료(유지) | `AuthService` | `FlowTest`, `screens.test.jsx` |
| `FR-A04` | P1 | UC-01 | `/app/account` | 완료(유지) | `LoginTokenService` | `ApiSecurityTest` |
| `FR-A05` | P2 | UC-01 | `/app/account` | 완료(유지) | `ProfileService` | `PeopleBoardFlowTest`, `people.test.jsx` |
| `FR-R01` | P0 | UC-02 | `/app/find`, `/app/requests/new` | 완료(유지) | `BoardingPointController`, `TmapWalkClient` | `BoardingPointControllerTest`, `RequestFlow.test.jsx` |
| `FR-R02` | P0 | UC-02 | `/app/requests/new` | 완료(유지) | `RideRequestService`, `KakaoRouteClient` | `SoloRouteLookupTest`, `TmapWalkClientTest` |
| `FR-R03` | P0 | UC-02 | `/app/requests/new` | 전환 필요 *(결제수단 등록 검증 추가)* | `RideRequestService` | `FlowTest`, `CommuteFlowTest` |
| `FR-R04` | P0 | UC-02 | `/app/requests/:id` | 완료(유지) | `RideRequestService` | `FlowTest`, `ConfirmationConcurrencyTest` |
| `FR-M01` | P0 | UC-03 | `/app/requests/:id` | 완료(유지) *(1% 여유분 제거만 조정)* | `SlotService`, `MatchingRound`, `RouteGroupVerifier` | `AutoMatchingScenarioTest`, `MatchingRoundTest`, `MatchingDeadlineRuleTest`, `RouteGroupVerifierTest` |
| `FR-M02` | P0 | UC-03 | `/app/requests/:id` | 완료(유지) | `WaitingInsightService`, `ApiController` | `RequestFlow.test.jsx`, `screens.test.jsx` |
| `FR-M03` | P0 | UC-03 | `/app/trips/:partyId` | 완료(유지) *(예상 분담금 표기 정비)* | `PartyService`, `PartyShrinker`, `PartyChangeService` | `PaymentFlowTest`, `partyLayout.test.jsx` |
| `FR-M04` | P1 | UC-03, UC-11 | `/app/trips/:partyId/route` | 완료(유지) | `PartyService`, `RoutePathStore` | `uiPolish.test.jsx`, `convenience.test.jsx` |
| `FR-M05` | P1 | UC-04 | `/app/find` | 전환 필요 *(선결제 조건 제거)* | `BoardService`, `PartyJoinService`, `JoinCompletionService` | `PeopleBoardFlowTest`, `people.test.jsx` |
| `FR-B01` | P0 | UC-05 | `/app/trips/:partyId` | 완료(유지) | `PartyReadinessService`, `PartyService` | `PaymentFlowTest`, `rideDecisions.test.jsx` |
| `FR-B02` | P0 | UC-11 | `/app/trips/:partyId` | 완료(유지) | `BoardingCodeService` | `BoardingCodeFormatTest`, `boardingCode.test.jsx` |
| `FR-B03` | P0 | UC-05 | `/app/trips/:partyId` | 완료(유지) | `BoardingCodeService` | `BoardingCodeFormatTest`, `boardingCode.test.jsx` |
| `FR-B04` | P0 | UC-05, UC-11 | `/app/trips/:partyId` | 완료(유지) | `PartyService`, `RideConfirmationService` | `AuditFixesTest`, `rideDecisions.test.jsx` |
| `FR-B05` | P0 | UC-05 | `/app/trips/:partyId` | 완료(유지) | `NoShowService`, `PartyService` | `PaymentFlowTest`, `AuditFixesTest` |
| `FR-B06` | P0 | UC-09 | `/app/account/cases` | 완료(유지) | `NoShowService`, `NoShowPolicy` | `NoShowCaseVisibilityTest`, `reviews.test.jsx` |
| `FR-S01` | P0 | UC-01 | `/app/account/payments` | **전환 필요 (신규)** | `PaymentMethodService` *(예정)* | *(신규 테스트 추가)* |
| `FR-S02` | P0 | UC-12 | `/app/trips/:partyId` | 전환 필요 *(버전 증가·확인 무효화 추가)* | `SettlementService`, `MediaFiles` | `PaymentFlowTest`, `settlement.test.jsx` |
| `FR-S03` | P0 | UC-07 | `/app/trips/:partyId` | 완료(유지) *(실제 요금 기준 호출)* | `SettlementCalculator` | `SettlementCalculatorTest` |
| `FR-S04` | P0 | UC-07 | `/app/trips/:partyId` | **전환 필요** *(확인형 후불 1회 결제)* | `PaymentService`, `SettlementService`, `TossPaymentGateway` | `PaymentFlowTest`, `TossPaymentGatewayTest`, `payments.test.jsx` |
| `FR-S05` | P0 | UC-09 | `/app/trips/:partyId` | 완료(유지) | `SettlementService` | `PaymentFlowTest`, `settlement.test.jsx` |
| `FR-S06` | P0 | UC-07 | `/app/trips/:partyId` | **전환 필요** *(전원 확인 즉시/6시간, 적립금 제거)* | `SettlementService`, `PaymentLifecycleService`, `PayoutLedger` | `PaymentFlowTest`, `PaymentFlowMysqlTest` |
| `FR-S07` | P1 | UC-08, UC-12 | `/app/account/payments` | 전환 필요 *(미납·수납·모의지급 분리 표시)* | `PaymentService`, `SettlementService` | `payments.test.jsx`, `settlement.test.jsx` |
| `FR-S08` | P1 | UC-08 | `/app/trips` | 완료(유지) | `RideHistoryService` | `screens.test.jsx`, `frontend/e2e/flow.spec.js` |
| `FR-C01` | P1 | UC-06 | `/app/messages/:partyId` | 완료(유지) *(3.0 독립 메시지함 이관)* | `PartyChatService` | `PartyChatFlowTest`, `chat.test.jsx` |
| `FR-C02` | P1 | UC-06 | `/app/notifications` | 완료(유지) | `NotificationService`, `PushService`, `MailService` | `PushServiceTest`, `MailOutboxTest` |
| `FR-C03` | P1 | UC-10 | `/app/trips/:partyId` | 완료(유지) | `MemberSafetyService`, `ReportPolicy` | `MemberSafetyFlowTest`, `screens.test.jsx` |
| `FR-C04` | P2 | UC-06 | `/app/messages/:partyId` | 완료(유지) | `PartyChatService`, `MediaRetention` | `PartyChatFlowTest`, `chat.test.jsx` |
| `FR-C05` | P2 | UC-08 | `/app/trips/:partyId` | 완료(유지) | `ProfileService` | `PeopleBoardFlowTest`, `people.test.jsx` |
| `FR-C06` | P2 | UC-08 | `/app/trips` | 완료(유지) | `RideHistoryService` | `format.test.js` |
| `FR-O01` | P0 | UC-13 | `/admin/reviews` | 완료(유지) | `AdminService` | `AdminNoShowEvidenceTest`, `reviews.test.jsx` |
| `FR-O02` | P0 | UC-13 | `/admin/reviews` | 완료(유지) | `AdminService` | `AuditFixesTest`, `reviews.test.jsx` |
| `FR-O03` | P0 | UC-13 | `/admin/reviews` | 완료(유지) | `AdminService` | `AuditFixesTest`, `reviewFixes.test.jsx` |
| `FR-O04` | P1 | UC-14 | `/admin/restrictions` | 완료(유지) | `AdminService`, `MemberSafetyService` | `MemberSafetyFlowTest`, `reviews.test.jsx` |
| `FR-O05` | P1 | UC-14 | `/admin/*` | 전환 필요 *(적립금 제거, 미납 관리 중심)* | `AdminService` | `ApiSecurityTest`, `reviews.test.jsx` |
| `FR-O06` | P2 | UC-14 | `/admin/overview` | 완료(유지) | `FareAccuracyService` | `screens.test.jsx` |

---

## 3. 현행 코드(`main`) 자동 테스트 기준선 (2026-11-25 실측)

- **프런트엔드 (`cd frontend && npm test -- --run && npm run lint`)**: **18개 파일 / 164개 테스트 전원 통과**, ESLint 오류 0건
- **매칭 엔진 (`JAVA_HOME=ms-21.0.12.1`, `./mvnw test`)**: **77개 실행 / 실패 0 / 오류 0 / 건너뜀 0**
- **서버 (`JAVA_HOME=ms-21.0.12.1`, `./mvnw test`)**: **514개 중 382개 실행 전원 통과 (실패 0 / 오류 0)**, 132개 건너뜀(`MYSQL_TEST_URL` 미설정 시 MySQL 전용 통합·마이그레이션 테스트 자동 건너뜀)
- **전환 원칙:** 40개 FR 중 **32개는 현행 구현을 그대로 보존**하고, **3계층 결제·정산 관련 8개 FR(`FR-R03`, `FR-M05`, `FR-S01`, `FR-S02`, `FR-S04`, `FR-S06`, `FR-S07`, `FR-O05`)** 만 v2.0 정책에 맞춰 집중 전환한다.

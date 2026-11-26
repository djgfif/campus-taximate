# 요구사항 추적표 (v2.0)

> **목적:** `v2.0 FR(40개) ↔ UC(14개) ↔ 시퀀스 다이어그램(3종) ↔ 목표 UI 경로 ↔ 백엔드 구현/전환 상태 ↔ 자동 테스트`를 한 표로 연결한다.
> **구현 상태 표기:** `완료(유지)` = 기존 구현 보존 / `완료` = v2.0(결제수단 등록·확인형 후불 1회 결제) 전환 완료.

---

## 1. 시퀀스 다이어그램 ↔ 유스케이스 ↔ FR 연결

| 시퀀스 다이어그램 | 담당 유스케이스 | 담당 FR | 핵심 백엔드 클래스 |
|---|---|---|---|
| **시퀀스 ① 요청과 자동 매칭** | `UC-02`, `UC-03`, `UC-04` | `FR-R01~R04`, `FR-M01~M05` | `RideRequestService`, `SlotService`, `MatchingRound`, `RouteGroupVerifier` |
| **시퀀스 ② 탑승 인증과 운행** | `UC-05`, `UC-11` | `FR-B01~B06` | `PartyReadinessService`, `BoardingCodeService`, `PartyService`, `NoShowService` |
| **시퀀스 ③ 실제 요금·확인형 1회 결제·정산** | `UC-07`, `UC-08`, `UC-09`, `UC-12`, `UC-13` | `FR-S01~S08`, `FR-O01~O03` | `SettlementService`, `PaymentService`, `PaymentLifecycleService`, `AdminService` |

---

## 2. 전체 요구사항 추적표 (FR 40개)

| FR | 등급 | UC | 목표 화면 경로 | 구현 상태 | 핵심 구현 클래스 | 기준선 검증 테스트 |
|---|---|---|---|---|---|---|
| `FR-A01` | P0 | UC-01 | `/register` | 완료(유지) | `AuthService`, `PasswordHasher` | `AuthConcurrencyTest`, `FlowTest` |
| `FR-A02` | P0 | UC-01 | `/login` | 완료(유지) | `AuthService`, `LoginTokenService` | `ApiSecurityTest`, `FlowTest` |
| `FR-A03` | P1 | UC-01 | `/reset-password` | 완료(유지) | `AuthService` | `FlowTest`, `screens.test.jsx` |
| `FR-A04` | P1 | UC-01 | `/app/account` | 완료(유지) | `LoginTokenService` | `ApiSecurityTest` |
| `FR-A05` | P2 | UC-01 | `/app/account` | 완료(유지) | `ProfileService` | `PeopleBoardFlowTest`, `people.test.jsx` |
| `FR-R01` | P0 | UC-02 | `/app/find`, `/app/requests/new` | 완료(유지) | `BoardingPointController`, `TmapWalkClient` | `BoardingPointControllerTest`, `RequestFlow.test.jsx` |
| `FR-R02` | P0 | UC-02 | `/app/requests/new` | 완료(유지) | `RideRequestService`, `KakaoRouteClient` | `SoloRouteLookupTest`, `TmapWalkClientTest` |
| `FR-R03` | P0 | UC-02 | `/app/requests/new` | 완료 *(결제수단 등록 검증 적용)* | `RideRequestService`, `PaymentMethodService` | `PostpaidPaymentGuardTest`, `PostpaidMatchingAndJoinTest`, `FlowTest`, `PaymentMethodsAndConversations.test.jsx` |
| `FR-R04` | P0 | UC-02 | `/app/requests/:id` | 완료(유지) | `RideRequestService` | `FlowTest`, `ConfirmationConcurrencyTest` |
| `FR-M01` | P0 | UC-03 | `/app/requests/:id` | 완료(유지) *(1% 여유분 제거만 조정)* | `SlotService`, `MatchingRound`, `RouteGroupVerifier` | `AutoMatchingScenarioTest`, `MatchingRoundTest`, `MatchingDeadlineRuleTest`, `RouteGroupVerifierTest` |
| `FR-M02` | P0 | UC-03 | `/app/requests/:id` | 완료(유지) | `WaitingInsightService`, `ApiController` | `RequestFlow.test.jsx`, `screens.test.jsx` |
| `FR-M03` | P0 | UC-03 | `/app/trips/:partyId` | 완료(유지) *(예상 분담금 표기 정비)* | `PartyService`, `PartyShrinker`, `PartyChangeService` | `PaymentFlowTest`, `partyLayout.test.jsx` |
| `FR-M04` | P1 | UC-03, UC-11 | `/app/trips/:partyId/route` | 완료(유지) | `PartyService`, `RoutePathStore` | `uiPolish.test.jsx`, `convenience.test.jsx` |
| `FR-M05` | P1 | UC-04 | `/app/find` | 완료 *(선결제 없이 즉시 합류)* | `BoardService`, `PartyJoinService`, `JoinCompletionService` | `PostpaidMatchingAndJoinTest`, `PeopleBoardFlowTest`, `people.test.jsx` |
| `FR-B01` | P0 | UC-05 | `/app/trips/:partyId` | 완료(유지) | `PartyReadinessService`, `PartyService` | `PaymentFlowTest`, `rideDecisions.test.jsx` |
| `FR-B02` | P0 | UC-11 | `/app/trips/:partyId` | 완료(유지) | `BoardingCodeService` | `BoardingCodeFormatTest`, `boardingCode.test.jsx` |
| `FR-B03` | P0 | UC-05 | `/app/trips/:partyId` | 완료(유지) | `BoardingCodeService` | `BoardingCodeFormatTest`, `boardingCode.test.jsx` |
| `FR-B04` | P0 | UC-05, UC-11 | `/app/trips/:partyId` | 완료(유지) | `PartyService`, `RideConfirmationService` | `AuditFixesTest`, `rideDecisions.test.jsx` |
| `FR-B05` | P0 | UC-05 | `/app/trips/:partyId` | 완료(유지) | `NoShowService`, `PartyService` | `PaymentFlowTest`, `AuditFixesTest` |
| `FR-B06` | P0 | UC-09 | `/app/account/cases` | 완료(유지) | `NoShowService`, `NoShowPolicy` | `NoShowCaseVisibilityTest`, `reviews.test.jsx` |
| `FR-S01` | P0 | UC-01 | `/app/account/payments` | 완료 | `PaymentMethodService`, `PaymentMethodController`, `PaymentMethodsPage.jsx` | `PaymentMethodServiceTest`, `PaymentMethodsAndConversations.test.jsx` |
| `FR-S02` | P0 | UC-12 | `/app/trips/:partyId` | 완료 *(리비전 증가·확인 무효화 적용)* | `SettlementService`, `PostpaidOrderSyncService`, `MediaFiles` | `PostpaidSettlementCalculationTest`, `PostpaidSettlementConcurrencyTest`, `PostpaidSettlement.test.jsx` |
| `FR-S03` | P0 | UC-07 | `/app/trips/:partyId` | 완료(유지) *(실제 요금 기준 호출)* | `SettlementCalculator` | `SettlementCalculatorTest` |
| `FR-S04` | P0 | UC-07 | `/app/trips/:partyId` | 완료 *(확인형 후불 1회 결제)* | `SettlementService`, `PaymentService`, `PostpaidOrderSyncService`, `TossPaymentGateway`, `SettlementPage.jsx`, `SettleScreens.jsx` | `PostpaidSettlementConcurrencyTest`, `PostpaidSettlement.test.jsx`, `frontend/e2e/flow.spec.js` |
| `FR-S05` | P0 | UC-09 | `/app/trips/:partyId` | 완료(유지) | `SettlementService` | `PaymentFlowTest`, `settlement.test.jsx` |
| `FR-S06` | P0 | UC-07 | `/app/trips/:partyId` | 완료 *(전원 확인 즉시/6시간 확정·수납 정산)* | `SettlementService`, `PaymentLifecycleService`, `PostpaidOrderSyncService`, `PayoutLedger` | `PostpaidSettlementCalculationTest`, `PostpaidSettlementConcurrencyTest`, `FlowTest` |
| `FR-S07` | P1 | UC-08, UC-12 | `/app/account/payments` | 완료 *(확인·결제/수납/미수납 분리 표시)* | `PaymentService`, `PartyService`, `SettlementPage.jsx`, `SettleScreens.jsx` | `PostpaidSettlementConcurrencyTest`, `PostpaidSettlement.test.jsx`, `settlement.test.jsx` |
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
| `FR-O05` | P1 | UC-14 | `/admin/*` | 완료 *(적립금 UI 숨김·미납 관리 중심)* | `AdminService`, `ReviewScreens.jsx` | `PostpaidSettlementCalculationTest`, `PostpaidSettlement.test.jsx`, `ApiSecurityTest`, `reviews.test.jsx` |
| `FR-O06` | P2 | UC-14 | `/admin/overview` | 완료(유지) | `FareAccuracyService` | `screens.test.jsx` |

---

## 3. v2.0 전환 완료 자동 테스트 검증 결과 (2026-11-26 실측)

- **프런트엔드 (`cd frontend && npm test -- --run && npm run lint`)**: **23개 파일 / 197개 테스트 전원 통과**, ESLint 오류 0건
- **Playwright E2E (`cd frontend && npx playwright test --reporter=list`)**: **`pc` 3건 + `phone` 3건 총 6건 전원 통과**
- **매칭 엔진 (`./mvnw test`, JDK 21)**: **77개 실행 / 실패 0 / 오류 0 / 건너뜀 0**
- **서버 (`./mvnw test`, JDK 21)**: **총 565개 중 H2 432개 실행 전원 통과 (실패 0 / 오류 0)**, MySQL 전용 133개(`MYSQL_TEST_URL` 미설정 시 스킵) 포함 전체 실행 환경에서 **MySQL 8.4.3 연동 143건(`MigrationSchemaTest` 10건 + `BoardingMigrationMysqlTest` 3건 + `PaymentFlowMysqlTest` 124건 + `AuthConcurrencyMysqlTest` 5건 + `ConfirmationConcurrencyMysqlTest` 1건) 전원 통과 (실패 0 / 오류 0)**
- **Docker Compose 라이브 스모크 (`docker compose up --build -d`)**: **MySQL 8.4.3 + 앱 빌드·기동 성공**, Flyway V1~V23(`V23__postpaid_v3_foundation.sql`) 적용 및 Hibernate 검증 통과, `/actuator/health` `UP`, `/api/config` `"paymentFlowVersion":3` 및 `/` `200 OK` 확인 후 컨테이너 정리 완료
- **전환 완료 요약:** 40개 FR 중 32개 핵심 도메인을 보존하고, **3계층 결제·정산 전환 대상 8개 FR(`FR-R03`, `FR-M05`, `FR-S01`, `FR-S02`, `FR-S04`, `FR-S06`, `FR-S07`, `FR-O05`)** 을 v2.0 후불 일시결제 정책(`app.payment.flow-version=3`)으로 전환 완료하였다.

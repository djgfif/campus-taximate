# v2.0 정산 전환 + Taximate 3.0 실행 로드맵

> **원칙:** 기획과 설계는 [service-overview.md](../01-product/service-overview.md) · [architecture-and-policy.md](../03-design/architecture-and-policy.md) 로 **잠금 완료**했다.
> **40개 FR 중 32개는 현행 구현을 그대로 보존**하고, **3계층 결제·정산 8개 FR**과 **프런트 UI(Taximate 3.0)** 만 단계적으로 전환한다.

---

## 1. 절대 건드리지 않는 것 vs 전환하는 것

| 구분 | 대상 | 이유 |
|---|---|---|
| **고정 (회귀 금지)** | 1계층 `matching-engine/`, 2계층 `SettlementCalculator`, 인증·탑승번호(S45)·채팅·관리자 심사, 기존 Flyway `V1~V22` | 이미 500+ 테스트와 C8/C11/C13으로 검증 완료 |
| **3계층 정산 전환 (8개 FR)** | 결제수단 사전 등록(`FR-S01`), 선결제·적립금 제거, 영수증 버전·확인형 후불 1회 결제(`FR-S02`, `FR-S04`, `FR-S06`, `FR-S07`), 6시간 자동 확정, 미납 제한 | v2.0 3대 정산 원칙 구현 (`V23+` 신규 마이그레이션으로 추가) |
| **UI/IA 개편 (Taximate 3.0)** | `frontend/src/` 8폴더 재편, `#145A46` 디자인 토큰, `Public/Student/Admin/Demo` 4레이아웃 격리, 지도 없는 홈, 이용 상세 중심 + 독립 메시지함 | 포트폴리오·사용성 완성도 확보 |
| **문서·PPT·포트폴리오** | 기준 명세 기반 최종 발표 PPT 25장 구성 + 포트폴리오형 `README.md` | 코드·설계·발표 100% 일치 |

---

## 2. 단계별 실행 순서 (구현 착수 시)

1. **Step 0 — 기준선 브랜치 분리**: 현재 미커밋 7개(`demoPayerCode`) 정리 후 `feat/v2-postpaid-taximate3` 브랜치 생성
2. **Step 1 — 백엔드 3계층 정산 전환 (8개 FR + `GET /api/me/conversations`)**
   - `V23__postpaid_settlement.sql`: 결제수단 등록 테이블, 정산 버전·확인 기록 테이블 추가 (기존 `V1~V22` 수정 금지)
   - `QuotedRouteClient` margin `0%` 전환, `PaymentService`·`SettlementService` 확인형 후불 1회 결제 + 전원 확인 즉시/6시간 자동 확정 + 24시간 미납 제한
   - `JDK 21 ./mvnw test` 전체 통과 + 신규 회귀 테스트 추가
3. **Step 2 — 프런트 Taximate 3.0 리뉴얼 (PR 1~6)**
   - **PR 1:** 디자인 토큰(`#145A46`) + 8폴더 구조 + `Public/Student/Admin/Demo` 4레이아웃 격리 + 시연 버튼 제거
   - **PR 2:** 지도 없는 요청 중심 홈(`/app`) + 동승 찾기(`/app/find`, `/app/requests/new`)
   - **PR 3:** 내 이용(`/app/trips`) + `TripViewModel` 이용 상세(`/app/trips/:id` — 영수증 확인 & `[확인하고 결제]` 1버튼 포함)
   - **PR 4:** 독립 메시지함(`/app/messages`, `/app/messages/:id`) + `PartyChat` 엔진 이관
   - **PR 5:** 관리자 좌측 사이드바 포털(`/admin/*`) + 시연 콘솔 격리(`/demo/*`)
   - **PR 6:** 공개 랜딩(`/`) + 도움말 + 접근성/E2E 회귀 + 포트폴리오형 `README.md`
4. **Step 3 — 최종 발표 PPT 재구성 & 팀원 PC 재현**
   - 확정된 `docs/01~05` 기준 25장 PPT + 부록 + 최종 발표자 확정

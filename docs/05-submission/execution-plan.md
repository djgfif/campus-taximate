# v2.0 정산·UI 단계별 전환 계획

> **기준 문서:** [service-overview.md](../01-product/service-overview.md) · [architecture-and-policy.md](../03-design/architecture-and-policy.md) · [traceability.md](../02-requirements/traceability.md)
> 40개 FR 중 **32개는 현행 구현을 그대로 보존**하고, **3계층 결제·정산 8개 FR**과 **프런트엔드 정보 구조**만 단계적으로 전환한다.

---

## 1. 보존 영역 vs 전환 영역

| 구분 | 대상 | 설명 |
|---|---|---|
| **보존 (회귀 금지)** | 1계층 `matching-engine/`, 2계층 `SettlementCalculator`, 인증·탑승번호(S45)·채팅·관리자 심사, 기존 DB 마이그레이션 `V1~V22` | 자동 테스트 및 공개 데이터 모의실험(C8·C11·C13)으로 검증된 핵심 도메인 유지 |
| **3계층 정산 전환 (8개 FR)** | 결제수단 사전 등록(`FR-S01`), 선결제·적립금 제거, 영수증 버전·확인형 후불 1회 결제(`FR-S02`, `FR-S04`, `FR-S06`, `FR-S07`), 6시간 자동 확정, 미납 제한 | v2.0 3대 정산 원칙 반영 (`V23+` 신규 마이그레이션으로 추가) |
| **프런트엔드 개편** | 기능별 폴더 재편, 디자인 토큰, `Public/Student/Admin/Demo` 4레이아웃 격리, 지도 없는 요청 중심 홈, 이용 상세 중심 + 독립 메시지함 | 역할 경계 분리 및 사용자 과업 중심 UX 개선 |

---

## 2. 단계별 구현 순서

1. **1단계 — 백엔드 3계층 정산 전환 (8개 FR + 메시지함 목록 API)**
   - 신규 마이그레이션(`V23+`): 결제수단 등록, 정산 버전·확인 기록 테이블 추가 (기존 `V1~V22` 불변)
   - `QuotedRouteClient` 여유분 `0%` 전환, `PaymentService`·`SettlementService` 확인형 후불 1회 결제 + 전원 확인 즉시/6시간 자동 확정 + 24시간 미납 제한
   - 독립 메시지함용 `GET /api/me/conversations` 경량 조회 API 추가
   - 전체 엔진·서버 자동 테스트 및 신규 회귀 테스트 통과 확인
2. **2단계 — 프런트엔드 리뉴얼 (PR 1~6)**
   - **PR 1:** 디자인 토큰, 기능별 폴더 구조, `Public/Student/Admin/Demo` 4레이아웃 격리, 기존 URL 호환 리다이렉트
   - **PR 2:** 지도 없는 요청 중심 홈(`/app`), 동승 찾기·요청 생성(`/app/find`, `/app/requests/new`)
   - **PR 3:** 내 이용(`/app/trips`), `TripViewModel` 기반 이용 상세(`/app/trips/:id` — 영수증 확인 및 `[확인하고 결제]` 포함)
   - **PR 4:** 독립 메시지함(`/app/messages`, `/app/messages/:id`) 및 `PartyChat` 엔진 이관
   - **PR 5:** 관리자 좌측 사이드바 포털(`/admin/*`) 및 시연 콘솔(`/demo/*`) 분리
   - **PR 6:** 공개 랜딩(`/`)·도움말·반응형/접근성 점검 및 전체 E2E 회귀 검증
3. **3단계 — 최종 검증 및 배포 재현**
   - 백엔드·프런트엔드·E2E 전체 테스트 재실행 및 Docker Compose 기반 신규 환경 재현 확인

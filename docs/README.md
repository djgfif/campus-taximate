# 캠퍼스 택시메이트 (Campus Taximate) — 설계 문서 안내

> **팀명:** 택시타는사람들 (대진대학교 컴퓨터공학과 캡스톤디자인)
> **기준일:** 2026-11-25 (v2.0 설계 기준)

문제 정의부터 요구사항, 유스케이스, 3계층 아키텍처·정산 의사결정, 요구사항 추적표, 법적 유효 범위까지 **단일 기준 명세**로 정리한 문서입니다.

---

## 문서 목록 (읽는 순서)

| 순서 | 문서 | 내용 |
|---|---|---|
| 1 | [service-overview.md](01-product/service-overview.md) | 서비스 정의, 핵심 가치 3가지, 포함/제외 경계, 6단계 생명주기, 3대 정산 원칙, MVP(P0/P1/P2 + Demo), 용어 사전 |
| 2 | [functional-requirements.md](02-requirements/functional-requirements.md) | 기능적 요구사항 40개(한 줄·P0/P1/P2·테스트 기준), 실측 기반 비기능적 요구사항 7개, 제약사항 3개 |
| 3 | [use-cases.md](02-requirements/use-cases.md) | 외부 처리 중심 4액터(`비회원·학생·결제자(상속)·관리자`) UML 다이어그램, 1장 유스케이스 명세표(14개·FR 매핑), 핵심 6종 상세 명세 |
| 4 | [traceability.md](02-requirements/traceability.md) | `FR 40개 ↔ UC 14개 ↔ 시퀀스 3종 ↔ 구현 클래스 ↔ 테스트` 추적표 및 테스트 기준선 |
| 5 | [architecture-and-policy.md](03-design/architecture-and-policy.md) | 3계층 독립 아키텍처, 정산 정책 진화 근거(C10·C11·C14 → 후불 1회 결제), 정산 3단계 분리·버전 경합 방어, 노쇼·미납 규칙 |
| 6 | [legal-scope.md](05-submission/legal-scope.md) · [execution-plan.md](05-submission/execution-plan.md) | 법령별 시연 유효 범위 vs 상용 전환 요건, v2.0 정산·UI 단계별 전환 계획 |

---

## 핵심 설계 요약

1. **3계층 독립 구조:** `Matching Engine(순수 Java)` · `Fare Allocation Engine(순수 Java)` · `Payment & Settlement Adapter`를 분리해 결제 방식이 바뀌어도 핵심 도메인이 흔들리지 않게 설계.
2. **자동 매칭 검증:** 회차별 완전탐색 최적해 대비 손실 `0.00~0.03%p`(C8), 2~3명 마감 대기 우위(C11), 지연 15분·절약 10% 기준 8/9 파레토 경계(C13).
3. **정산 정책 진화:** `115~125% 선결제`의 잦은 부분환불(77~95%, C10)과 `확정 분담금·적립금`의 초과 납부·공통 충격 적자 위험(C11·C14)을 확인한 뒤, **적립금 폐지 · 실제 요금 비율 · 정산 확인형 후불 1회 결제**로 수렴.
4. **테스트 기준선 (2026-11-25 실측):** 자동 테스트 **623건 실행 전원 통과**(엔진 77 · 서버 382 · 프런트 164), API p95 **27~51ms**, 매칭 100건 **최대 0.49초**, 동시 확정 100회 중복 **0건**.

---

## 기존 이력 문서 (참고·보존)
- [openapi.json](openapi.json) — 현행 REST API 명세
- [boarding-and-completion-spec.md](boarding-and-completion-spec.md) — S45 탑승 인증·운행·소명 규칙
- [completion-status.md](completion-status.md) — 3~4차 피드백 반영 관리표
- [service-boundary.md](service-boundary.md) — 3차 법적 검토 초안 *(최신 기준은 [legal-scope.md](05-submission/legal-scope.md))*

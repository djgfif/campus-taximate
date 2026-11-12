# 3차 화면용 API 목록 (2026-11-12 기준, feature/3rd-spec)

백엔드는 아래 API까지 구현·테스트를 마쳤다. 화면이 아직 없는 것 위주로 정리했다. 규칙의 근거는 `3차_매칭결제_명세.md`의 장 번호다.
로그인은 기존과 같이 세션 쿠키. 오류는 `{"code": "...", "message": "..."}`이고 `message`는 그대로 사용자에게 보여 줘도 된다.
전체 목록과 응답 형식은 서버 실행 중 `http://localhost:8080/swagger-ui.html`에서 볼 수 있다.

## 1. 일행 화면에 새로 들어가는 값 (`GET /api/parties/{id}`, `/api/state`의 parties)

| 필드 | 뜻 | 화면에서 |
|---|---|---|
| `instant` | 즉시 일행 여부 | 출발 시각 대신 "예상 출발" 표시 (이미 연결됨) |
| `callReady`, `canCall`, `notReadyReasons` | 호출 준비 상태 | 결제자 "택시 호출" 버튼 켜기·끄기, 꺼졌을 때 이유 목록 (이미 연결됨) |
| `canConfirmRide`, `myRideAnswer` | 운행 확인 질문 대상인지, 내 답 | "탔어요 / 못 탔어요" 버튼 (이미 연결됨) |
| `canLeaveAfterChange`, `changeExitFree`, `groupVersion`, `changes` | 일행이 바뀌었을 때 빠지기 | "변경돼서 빠지기" 버튼, 무료 여부 안내 (이미 연결됨) |
| `myConfirmedShare`, `myConfirmedMigrated` | 확정 때 내 예상 분담금 | "처음 안내된 내 몫"과 현재 몫 표시. 이관 출처는 학생 화면에서 숨김 |
| `successorId`, `successionDeadlineAt`, `successionStatus` | 결제자 승계 요청 | 일행 화면에서 승계 수락·거절 (4장) |
| `members[].pointName` | 하차 지점 | **결제자에게만 모든 사람의 지점 이름**이 온다. 결제자가 아니면 내 지점만 이름이고, 다른 사람은 `"2번째 하차"`처럼 순서만 온다(안전). `/api/parties/{id}/routes`의 공동 경로도 결제자가 아니면 내가 내리는 곳까지만 온다 |

## 2. 즉시 일행 준비·호출 (명세 5.7, 이미 화면 있음)
- `POST /api/parties/{id}/readiness` `{ "state": "ARRIVED" | "NEARBY" }` — 도착했어요 / 2분 안 도착
- `POST /api/parties/{id}/call` — 결제자 호출 표시 (`canCall`일 때만)

## 3. 노쇼 판정 (명세 8.3, **화면 없음**)
운행 시작 때 안 탄 사람은 "탑승 미확인" 조사 대상이 된다. 24시간 안에 일행이 표시하고 본인이 소명한다.

| 화면 | API | 누가 |
|---|---|---|
| 일행 화면의 "안 온 사람" 표시 | `POST /api/parties/{partyId}/members/{memberId}/boarding-votes` `{ "came": false }` (왔으면 `true`) | 같은 일행원 |
| 내 조사 목록 | `GET /api/boarding-cases` | 조사 당사자 |
| 일행의 조사 목록 | `GET /api/parties/{partyId}/boarding-cases` | 일행원 |
| 소명 입력 (24시간 안, 2,000자) | `POST /api/boarding-cases/{id}/explanation` `{ "explanation": "..." }` | 조사 당사자 |
| 보전 내역 | `GET /api/parties/{partyId}/compensation` | 일행원 |

- 24시간 뒤 "안 왔어요"만 있고 소명이 없으면 노쇼 확정, 그 밖에는 관리자 검토.
- 확정 노쇼는 늘어난 몫만 선결제에서 내고 나머지는 환불(8.4). 30일 2회면 7일 요청 제한.

## 4. 결제자 승계 (명세 8.4 S24·S32, 일행 화면 연결됨)
- 결제자가 안 오면 일행 표시를 받아 새 마지막 하차자에게 승계 요청이 간다(`successorId`가 나, `successionStatus`가 요청 중).
- `POST /api/parties/{partyId}/payer-succession` `{ "accept": true | false }`
- T + 5분(`successionDeadlineAt`)까지 수락하지 않으면 운행 미성사, 모두 전액 환불.

## 5. 관리자 화면 (명세 9장, **화면 없음**)
시연 관리자 계정: `demo-admin@daejin.ac.kr` (비밀번호는 다른 시연 계정과 같음). 일반 회원이 부르면 403.
판정·처리 요청에는 근거(`evidence`, 2,000자)가 필수이고, 처리마다 기록이 남는다.

| 화면 | 조회 | 처리 |
|---|---|---|
| 노쇼 검토 | `GET /api/admin/no-shows`, `GET /api/admin/no-shows/{id}` (상세에 일행 채팅 최근 50건) | `POST /api/admin/no-shows/{id}/decision` `{ "decision": "NO_SHOW" \| "NOT_NO_SHOW" \| "UNDETERMINED", "evidence": "..." }` |
| 요금 이의 | `GET /api/admin/fare-reviews`, 영수증 `GET /api/admin/parties/{id}/receipt` | `POST /api/admin/parties/{id}/fare-decision` `{ "accept": true, "correctedAmount": 15000, "evidence": "..." }` |
| 운행 답 엇갈림 | (요금 이의 목록과 같은 일행) | `POST /api/admin/parties/{id}/ride-decision` `{ "evidence": "..." }` — 영수증 있으면 운행, 없으면 미운행·전액 환불 |
| 신고·제한 | `GET /api/admin/reports`, `GET /api/admin/members/{id}/restrictions` | `POST /api/admin/members/{id}/restrictions` `{ "until": "...", "evidence": "..." }` (헤더 `Idempotency-Key`), `POST /api/admin/restrictions/{id}/release` |
| 미정산 | `GET /api/admin/overdue-payments` | `POST /api/admin/overdue-payments/{id}/handled` |
| 승차 지점 | `GET /api/admin/hubs` | `POST /api/admin/hubs`, `PUT /api/admin/hubs/{id}` (헤더 `Idempotency-Key`, 현장 확인 안 된 지점은 사용자에게 안 보임) |
| 처리 기록 | `GET /api/admin/audits` | — |

`UNDETERMINED`(판단 불가)는 명세 S35대로 노쇼 아님과 같게 처리된다(전액 환불, 기록 없음). 같은 판정을 두 번 보내도 한 번만 처리된다.

## 6. 합류 (명세 5.5, **화면 없음**)
확정된 일행(4명 미만)에 나중 요청이 들어올 수 있으면 회차가 자동으로 합류를 제안한다. 합류자는 5분 안에 선결제해야 합류가 확정되고, 그 전까지 기존 일행원 분담금은 바뀌지 않는다.
- `GET /api/joins` — 나에게 온 합류 제안(대기 요청 화면에서 "선결제하면 합류" 안내)
- `POST /api/parties/{id}/joins` `{ "requestId": 123 }` — 합류 요청
- 합류 선결제는 기존 결제 API(`POST /api/payments/orders`)를 그대로 쓴다.
- 합류가 확정되면 기존 일행원의 `groupVersion`이 오르고 `changes`에 기록된다. 분담금은 같거나 내려간다.

## 7. 참고
- 걷는 시간은 TMAP 보행자 경로로 받고, 실패하면 지금처럼 추정값과 "추정" 표시다.
- 서버가 잠깐 바쁠 때(DB 교착) `409` `{"code":"RETRY"}`가 올 수 있다. "잠시 뒤 다시 시도해 주세요"를 보여 주면 된다.


## 8. 시연 메뉴 (demo 프로필 전용)
- 모든 /api/demo/ 경로는 demo 프로필에서만 등록된다. app.demo.enabled=true만으로 운영에서 열리지 않는다.
- POST /api/demo/login만 로그인 없이 호출 가능. 다른 시연 API는 로그인 필요. 동일 출처·요청 제한은 기존 필터를 따른다.
- POST /api/demo/login { "account": "demo-f1" }: demo-f1~f4, demo-m1~m4, demo-admin만 허용. 세션 ID를 교체하고 해당 기기의 기존 유지 토큰을 폐기한 뒤 새 쿠키를 발급한다.
- POST /api/demo/match { "role": "PAYER" | "PASSENGER" }: 생략하면 기존처럼 PAYER. PASSENGER는 더 먼 등록 샘플 또는 목적지 연장선을 검사하며, 실제 도로 모드에서도 지연·절약·결제자 조건을 통과해야 저장한다. 응답은 기존 { status, message }이고 message에 내 역할을 포함한다.
- GET /api/demo/status: 아래 Progress 조회.
- POST /api/demo/next: 내 일행의 다음 주요 시점으로 이동하고 샘플만 결제·도착·금액 입력. 내 선결제·금액/영수증 입력·운행 답변·승계 답변·관리자 판정이 필요하면 멈추고 안내한다.
- POST /api/demo/scenario { "scenario": "NO_SHOW", "role": "PAYER" }: 아래 시나리오 준비. role 기본 PAYER, 승계 시나리오는 샘플 결제자를 위해 PASSENGER로 준비한다.

| scenario | 결과 |
|---|---|
| INSTANT | 요청이 없으면 즉시 요청을 만들고 즉시 매칭. 기존 예약을 임의로 변경하지 않음 |
| NO_SHOW | 샘플 한 명을 제외하고 도착·결제, T+5분에 운행 확인·금액 입력·부재 표시. 다음 단계에서 24시간 뒤 노쇼 판정·정산 확정 |
| JOIN | 남은 시연 계정으로 같은 중간 하차지에 합류 제안·선결제. 기존 합류 검증 그대로 적용 |
| FARE_OBJECTION | 운행·금액 입력 후 샘플이 요금 이의, 관리자 검토 상태 |
| PAYER_NO_SHOW | 샘플 결제자 부재 표시 → 승계 요청. 내 화면에서 수락·거절 |
| MY_SUCCESSION | 위 과정 뒤 내가 승계를 수락하는 과정까지 시연 |
| RIDE_UNCONFIRMED | 운행 시작 표시 없이 T+5분까지 이동, 운행 질문 표시·환불 없음 |

Progress: { now, partyId, stage, next, role, message, investigations: [{ memberId, status, deadlineAt }] }.
시나리오는 요청이 없으면 확인된 승차 지점과 시연 하차지로 생성하며, 이미 진행된 상태를 되돌리지 않는다. 일반 회원이 섞인 일행은 자동 조작을 거절한다.
자동 결제가 포함된 시나리오는 모의 PG에서만 가능하며 내 선결제도 준비 과정에서 처리한다. 토스 테스트 PG에서는 역할 선택 후 계정을 전환해 각자 결제창에서 직접 결제한다. 테스트 키·외부 결제 키를 만들어 승인하지 않는다.
확정·결제·도착·합류 메시지는 PartyChatService.send를 사용하여 기존 채팅 저장·권한·알림 경로를 따른다.
기존 POST /api/demo/run, /advance, /samples, /act는 유지한다. advance는 1~2880분, samples는 1~3명이다.
학생 이용 내역은 예상 요금 정확도 통계를 조회·표시하지 않으며 관리자 계정의 이용 내역에서만 표시한다. 기존 GET /api/stats/fare-accuracy는 유지한다.
대기 화면은 응답 state.now를 기준으로 경과 시간을 더하고, 지난 nextCheckAt은 시각 대신 "지금 조건을 다시 확인하고 있어요"로 표시한다. 시연에서는 시연 시각을 함께 표시한다.

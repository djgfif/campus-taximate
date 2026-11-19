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

## 3. 노쇼 판정 (명세 8.3, 화면 연결됨: `ReviewScreens.jsx`)

일행 정보 탭에서 동승 여부를 표시하고, 내 정보 → 내 탑승 확인·소명(`/boarding-cases`)에서 본인 조사와 마감·접수한 소명·판정 근거를 확인한다. 첫 표시는 최종 확인 뒤 제출하며, 첫 소명은 변경할 수 없다. 실패 시 초안을 보존하고 재시도할 수 있다.

결제자는 실제 금액 입력 화면에서도 ‘일행 탑승 확인’을 열 수 있다. 조사 응답의 `nickname`은 현재 일행에서 제외된 대상도 식별할 수 있게 한다. 소명 원문은 해당 조사 본인과 관리자만 받는다. 자동·수동 재조회 실패 시 마지막으로 확인한 내용과 작성 중인 초안을 유지하고 오류를 표시한다.
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

## 5. 관리자 화면 (명세 9장, 화면 연결됨: `/admin`)

관리자 계정의 내 정보 메뉴와 PC 상단에서 연다. 노쇼 검토, 요금·운행, 신고·제한, 미정산, 승차 지점, 처리 기록을 제공한다. 일반 계정은 화면에서 접근을 안내하고 API는 별도로 권한을 검증한다. 모든 처리는 근거가 필수이고, 지점·제한 등록의 동일 내용 재시도에는 같은 멱등키를 쓴다. 미정산 확인 기록은 결제 승인·채무 면제가 아니다.
시연 관리자 계정: `demo-admin@daejin.ac.kr` (비밀번호는 다른 시연 계정과 같음). 일반 회원이 부르면 403.
판정·처리 요청에는 근거(`evidence`, 2,000자)가 필수이고, 처리마다 기록이 남는다.

| 화면 | 조회 | 처리 |
|---|---|---|
| 노쇼 검토 | `GET /api/admin/no-shows`, `GET /api/admin/no-shows/{id}` (상세에 일행 채팅 최근 50건) | `POST /api/admin/no-shows/{id}/decision` `{ "decision": "NO_SHOW" \| "NOT_NO_SHOW" \| "UNDETERMINED", "evidence": "..." }` |
| 요금 이의 | `GET /api/admin/fare-reviews`, 영수증 `GET /api/admin/parties/{id}/receipt` | `POST /api/admin/parties/{id}/fare-decision` `{ "accept": true, "correctedAmount": 15000, "evidence": "..." }` |
| 운행 검토 | (요금 이의 목록과 같은 일행) | `POST /api/admin/parties/{id}/ride-decision` `{ "decision": "OPERATED", "evidence": "..." }` — 운행 또는 `NOT_OPERATED`를 근거와 함께 명시 |
| 신고·제한 | `GET /api/admin/reports`, `GET /api/admin/members/{id}/restrictions` | `POST /api/admin/members/{id}/restrictions` `{ "until": "...", "evidence": "..." }` (헤더 `Idempotency-Key`), `POST /api/admin/restrictions/{id}/release` |
| 미정산 | `GET /api/admin/overdue-payments` | `POST /api/admin/overdue-payments/{id}/handled` |
| 승차 지점 | `GET /api/admin/hubs` | `POST /api/admin/hubs`, `PUT /api/admin/hubs/{id}` (헤더 `Idempotency-Key`, 현장 확인 안 된 지점은 사용자에게 안 보임) |
| 처리 기록 | `GET /api/admin/audits` | — |

`UNDETERMINED`(판단 불가)는 명세 S35대로 노쇼 아님과 같게 처리된다(전액 환불, 기록 없음). 같은 판정을 두 번 보내도 한 번만 처리된다.

노쇼 조사 상세 `chat[]`는 `kind`와 `imageUrl`을 포함한다. 사진은 `GET /api/admin/no-shows/{caseId}/messages/{messageId}/image`에서 관리자만 조회하며, 조사의 일행과 메시지의 일행이 일치해야 한다. 기존 채팅과 동일한 보관기간이 지나거나 파일이 없으면 404, 응답은 `no-store`와 `nosniff`를 적용한다.

## 6. 합류 (명세 5.5, 화면 연결됨: `Board.jsx`, `RequestStatus.jsx`)
확정된 일행(4명 미만)에 나중 요청이 들어올 수 있으면 회차가 자동으로 합류를 제안한다. 합류자는 5분 안에 선결제해야 합류가 확정되고, 그 전까지 기존 일행원 분담금은 바뀌지 않는다.
- `GET /api/joins` — 나에게 온 합류 제안(대기 요청 화면에서 "선결제하면 합류" 안내)
- `POST /api/parties/{id}/joins` `{ "requestId": 123 }` — 합류 요청
- 합류 선결제는 기존 결제 API(`POST /api/payments/orders`)를 그대로 쓴다.
- 합류가 확정되면 기존 일행원의 `groupVersion`이 오르고 `changes`에 기록된다. 분담금은 같거나 내려간다.

## 7. 참고
- 걷는 시간은 TMAP 보행자 경로로 받고, 실패·한도 초과면 `503 WALK_UNAVAILABLE`로 요청을 거절한다(S44). 키가 없는 개발 환경에서만 직선거리 추정값과 "추정" 표시를 쓴다. 예외로 출발지가 승차 지점에서 10m 이내면 TMAP을 부르지 않고 같은 추정(1분, "추정")을 쓴다. TMAP이 같은 좌표를 400(code 1007, waypoints are too near)으로 거절해 지점 앞 요청이 항상 503이 되던 문제 때문이다(2026-11-19).
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

## 9. 일행 채팅 (카톡 수준, 화면 연결됨: `PartyChat.jsx`)
| 기능 | API |
|---|---|
| 최근 50개 / 새 메시지 / 이전 메시지 | `GET /api/parties/{id}/messages` (`?after=번호` 그 뒤, `?before=번호` 그 앞 50개). 응답에 `hasOlder`, `lastReadId` |
| 글 보내기 | `POST /api/parties/{id}/messages` `{ "text": "...", "clientId": "임의 문자열" }` — 같은 `clientId`로 다시 보내면 한 번만 저장(재전송 안전) |
| 사진 보내기 | `POST /api/parties/{id}/messages/image` (multipart `image`, 선택 `clientId`). JPG·PNG·WEBP, 5MB 이하, 파일 앞부분으로 형식 확인 |
| 사진 보기 | 메시지의 `imageUrl` (`GET /api/parties/{id}/messages/{mid}/image`, 현재 일행원만) |
| 읽음 표시 | `POST /api/parties/{id}/messages/read` `{ "lastId": 번호 }` — 읽은 위치는 앞으로만 감 |
| 실시간 | 기존 `/api/events` SSE에 `chat` 이벤트(데이터: 일행 번호). 화면은 `taximate:chat` 창 이벤트로 받아 그 채팅만 다시 읽는다 |

- 메시지 필드: `kind`(`TEXT`·`IMAGE`·`SYSTEM`), `unread`(보낸 사람을 뺀 현재 일행원 중 아직 안 읽은 사람 수, 카톡의 노란 숫자), `imageUrl`, `clientId`(내 메시지만). `SYSTEM`은 `memberId`가 `null`.
- 시스템 알림(자동): 일행 확정, 선결제 완료, 도착, 2분 안 도착, 일행원 빠짐, 합류, 운행 시작, 실제 금액 입력.
- 일행 목록·조회 응답의 `unreadChat`: 내가 안 읽은 메시지 수. 홈의 진행 중 일행에 빨간 숫자로 보인다.
- 1분에 10개까지 보낼 수 있다(넘으면 `429` `CHAT_TOO_FAST`). 휴대폰에서는 Enter가 줄바꿈이고 보내기 버튼으로 보낸다.

## 10. 사람이 보이는 기능 (화면 연결됨: `Board.jsx`, `People.jsx`, `SketchMap.jsx`)

| 기능 | 요청 | 응답·규칙 |
|---|---|---|
| 지금 모집 중 | `GET /api/board` | `parties[]`: 합류 가능한 하교 일행(같은 성별, 서로 피하기 없음, 합류 마감 전, 자리 남음). 출발 시각·거점·남은 자리·별명·공개 프로필. 다른 사람의 하차 지점은 주지 않는다. `waiting[]`: 시간대(1시간)·방향별 기다리는 사람 수. |
| 게시판에서 합류 | `POST /api/board/parties/{id}/join` `{pointName, pointLat, pointLon, memo}` | 그 일행 출발 시각을 포함하는 요청을 만들고 바로 합류를 신청한다(5.5 합류 규칙 그대로). 실패하면 만든 요청을 취소하고 오류를 돌려준다. 성공하면 내 요청이 `JOINING`이 되고, 선결제가 승인되면 일행원이 된다. |
| 같이 타는 사람 | `GET /api/parties/{id}/people` | 일행원만. `profile`(공개한 학과·학번), `rides`(완료한 탑승 수), `noShowFree`, `praises`(받은 칭찬 수), `myTags`(내가 이 사람에게 남긴 칭찬). |
| 칭찬 | `POST /api/parties/{id}/praises` `{targetId, tags}` | 운행이 끝난 일행(`AWAITING_FARE` 이후)에서 같이 탄 사람에게만. 태그 `ON_TIME`·`KIND`·`QUIET`·`CLEAR_PAY`. 같은 칭찬은 한 번만 쌓인다. 나쁜 평가는 받지 않는다(신고로 처리). |
| 내 공개 프로필 | `GET`·`PUT /api/me/profile` `{department, admissionYear, showProfile}` | 학과 40자 이하, 학번 두 자리(0~99). `showProfile=false`면 게시판과 일행 화면에 별명만 보인다. |
| 시연: 다른 학생 일행 | `POST /api/demo/board-party` | 로그인한 사람을 뺀 같은 성별 시연 계정 두 명이 하교 일행을 만든다. 요청이 없을 때만. |

- DB: `V15__profile_trust_review.sql` (`member.department`·`admission_year`·`show_profile`, `ride_review`).
- 지도: 카카오 지도를 못 불러오면 같은 좌표로 약도(`SketchMap`)를 그린다. 휴대폰 일행 화면과 요청 화면에도 작은 지도가 붙는다.
- 요청 화면: 출발지를 고르면 가장 가까운 승차 지점이 미리 골라지고, 시간은 "오늘 22:25 ~ 23:25 출발" 한 줄로 접혀 있다(눌러서 바꾸기).


## 팀원 제안: 실제 탑승 코드와 명시적 운행 시작 (V16)

이 방식은 교수 요구사항이 아니라 팀원 제안이다. 현재 공동 승차 흐름에 적용하며 레거시 `TO_SCHOOL` 신규 요청을 다시 허용하지 않는다.

| API | 권한과 의미 |
| --- | --- |
| `POST /api/parties/{id}/arrive` | 승차 지점 도착 표시. 탑승 확인이나 출발 처리를 하지 않는다. |
| `POST /api/parties/{id}/boarded` | 현재 결제자의 실제 탑승. 고정 길이 6자리 문자열 코드를 만들고 **실제 탑승 시각 + 5분** 창을 연다. 동일 요청 재시도는 코드나 마감을 바꾸지 않는다. |
| `GET /api/parties/{id}/boarding-code` | 현재 일행원용 상태: `generation`, `deadlineAt`, `status` (`WAITING`/`OPEN`/`EXPIRED`), `confirmed`, `remainingAttempts`, `canOpen`, `canStart`. `code`는 창이 열려 있는 동안 현재 결제자에게만 내려간다. |
| `POST /api/parties/{id}/boarding-code` | 승객이 `{ "code": "000042", "generation": 1 }`을 전송한다. 숫자가 아닌 문자열이며 선행 0을 보존한다. |
| `POST /api/parties/{id}/start` | 현재 결제자가 실제 탑승을 표시한 뒤 명시적으로 운행을 시작한다. 같은 출발 요청은 멱등적이다. |

승객별·generation별 오입력 기회는 5회다. 잘못된 형식도 현재 generation의 오입력에 포함된다. 오래된 generation, 만료, 일행 외 접근은 입력 횟수를 소모하지 않는다. 오입력 기록은 별도 트랜잭션으로 저장하고 일행 DB 잠금으로 동시 요청을 직렬화한다. 이미 확인된 승객의 동일 generation 재전송은 성공으로 처리한다. 마감 경계 시각부터 새 확인은 거부한다.

결제자 승계는 이전 코드를 폐기하며 이미 확인된 승객은 유지한다. 새 결제자는 다시 실제 탑승을 표시해야 새 5분 창을 열 수 있고, 승계 수락만으로 출발하지 않는다. 도착·스케줄러·운행 답변·영수증 입력도 출발을 대신하지 않는다.

미확인 승객이 있어도 결제자가 출발할 수 있다. 코드 창이 열려 있는 동안 승객을 제외하지 않으며 입력도 계속 받는다. 마감 뒤 남은 미확인 승객은 기존 24시간 소명·동료 표시·관리자 판정 절차로 보낸다. 코드 미입력 자체는 노쇼 확정 근거가 아니다. `UNDETERMINED` 보호와 S35 보전 계산은 유지한다. 실제 금액 제출은 코드 마감 뒤 가능하고, 최종 환불·추가 결제·지급은 필수 조사가 끝날 때까지 보류한다.

코드 값은 채팅·시스템 이벤트·SSE·요청 로그에 넣지 않는다. 진행 탭과 채팅 탭에서 역할별 코드 상태와 출발 버튼을 제공하며, 채팅 빠른 답장은 코드 값을 포함하지 않는다. 기존 조사/관리자 화면은 유지한다.

영수증 7일 삭제는 관리자 판정·정산 수정과 같은 일행 DB 잠금을 사용한다. 보관 만료 또는 실제 파일 유실은 `EVIDENCE_UNAVAILABLE`로 다루며, 영수증이 없다는 사실을 미운행의 근거로 바꾸지 않는다.

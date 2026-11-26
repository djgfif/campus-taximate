# 캠퍼스 택시메이트 UI/UX 품질 개선 및 실사용 검증 보고서

> **대상 브랜치:** `feat/ui-ux-polish` (기준: `main` · v3 후불 확인형 정산 기본 적용)
> **연계 문서:** [execution-plan.md](execution-plan.md) · [legal-scope.md](legal-scope.md) · [service-overview.md](../01-product/service-overview.md) · [architecture-and-policy.md](../03-design/architecture-and-policy.md)

---

## 1. 사전 감사 및 발견 결함

v3 확인형 후불 정산 및 프런트엔드 레이아웃 개편이 반영된 `main`을 기준으로 실제 Docker Compose 실행 환경(`http://localhost:8080`)과 전체 소스 코드를 점검한 결과, 상용 모빌리티 서비스 수준에 미달하거나 실사용 마찰을 유발하는 다음 결함을 확인했다.

1. **SPA 경로 직접 진입·새로고침 404 오류 (`WebConfig.java`)**
   - `/app/find`, `/app/messages`, `/app/account/payments`, `/demo`, `/find`, `/messages`, `/payments` 등 다수 활성 SPA 경로가 서버 뷰 컨트롤러에 등록되지 않아 브라우저 주소창 직접 입력이나 `F5` 새로고침 시 404 응답이 발생했다.
2. **디자인 토큰 명도 대비 저하 및 미정의 변수 (`tokens.css`)**
   - 강조 토큰 `--amber`가 `#145a46`(Deep Green)으로 전환된 뒤에도 `.rail li.me .pin`, `.mk.me`, `.mk.time`, `.road-car`, `.driver li span`에 어두운 잉크색(`#172521`)이 남아 약 1.8:1 수준으로 가독성이 떨어졌다.
   - 보조 텍스트 토큰(`--color-text-muted`, `--faint`, `--off-ink`)의 대비가 낮았고 `--shadow-elevated` 변수가 정의 없이 참조되고 있었다.
3. **이전 테마 잔재 및 미사용 CSS 규칙 (`MapPicker.jsx`, `RouteComparison.jsx`, `styles.css`, `reviews.css`)**
   - 지도 마커·경로 색상(`#f5b301`, `#f5b800`, `#8a5a00`)과 과거 레이아웃용 미사용 선택자(`.nav*`, `.demo-nav`, `.bar.floating`, `.steps`, `.say .push-on`, `.map-fallback`/`.mf-*`, `.home-map`/`.home-sheet`/`.where`/`.recent`/`.text-row-value`, `.review-nav`, `.screen.solo:has(.admin-page)`)가 남아 유지보수성과 스타일 예측 가능성을 해쳤다.
   - 데스크톱(`>= 1024px`)에서도 비어 있는 하단 `.dock` DOM이 출력되었고, 모바일 하단 `.tabbar`와 5개 탭 배치의 반응형 규칙 정합성이 필요했다.
4. **레이어(`z-index`) 충돌 및 좁은 화면 침범**
   - 기사님 안내 오버레이(`.driver`, `z-index: 80`)가 토스트 알림(`.toasts`, `z-index: 70`)을 가려, 목적지 복사나 호출 완료 피드백이 사용자에게 보이지 않았다.
   - 작은 모바일 화면에서는 `.chat-task` 최대 높이(`46dvh`)가 채팅 영역을 과도하게 압박할 위험이 있었다.
5. **정보 위계 및 v3 후불 정산 문구 불일치**
   - 홈(`/`)에서 진행 중인 이용이 있어도 3단계 소개 카드(`이용 방법`)보다 아래에 놓이거나 반복 이용자에게도 고정 설명이 계속 노출되었다.
   - `SettlementPage`에서 결제자 수금 내역(`PostpaidCollection`)이 이중으로 노출되었고, `RequestStatus`·`Board`·`DemoBar`·`History`·`TripDetailPage`·`PartyScreens`·`PartyShell` 일부 문구에 v2 선결제·보증금 기준 안내가 섞여 있었다.
   - 결제수단이 없는 사용자가 동승 요청 단계에서 막혔을 때 바로 결제수단 등록으로 이동하는 행동 유도 버튼이 비어 있었고, `ReceiptField`의 잘못된 파일 선택 시 입력값이 초기화되지 않았다.
   - 관리자 화면(`/admin`)은 모의 운영 장부 안내 배너가 반복 출력되고 탭별 판단 기준 설명과 목록 검색 필터가 부족했다.
   - 네트워크 끊김 시 원문 `"Failed to fetch"`가 그대로 노출되었고, `/api/boot` 초기 일시 오류나 로그아웃 흐름의 상태 정리가 매끄럽지 못했다.

---

## 2. 실제 서비스 벤치마크 분석

표면적인 컴포넌트 모방이 아니라 정보 조직 방식과 행동 유도 구조를 도출하기 위해 다음 7개 상용 서비스를 기준으로 분석했다.

| 참고 서비스 | 분석 초점 | 캠퍼스 택시메이트 적용 원칙 |
|---|---|---|
| **카카오 T** | 출발지·목적지 중심 진입과 호출·이동 상태 분리 | 홈을 소개 페이지가 아닌 등·하교 즉시 출발 패널 중심으로 두고, 활성 이용이 있으면 최상단에 배치 |
| **Uber** | 지도·작업 패널 결합과 즉각적 상태 피드백 | PC 요청 화면에서 입력 패널과 지도를 병렬 배치하고, 기사님 안내에서도 복사·호출 피드백 토스트가 가려지지 않게 보장 |
| **토스** | 한 화면 하나의 핵심 금액 강조 및 거래 상태 단순화 | 정산 화면 상단에 `.money-hero`(내 분담금·절약액)와 `.money-progress` 진행 막대를 두고, 모의 정산·지급 경계를 명시 |
| **BlaBlaCar** | 동승자 신뢰 정보와 비용 공정성 설명 | 학내 메일 인증 맥락, 6자리 탑승번호 검증, v3 실제 요금 비율 배분 근거(`FairnessNote`)를 부담 없는 요약으로 제공 |
| **당근** | 채팅과 거래 상태의 결합 | 일행방에서 채팅과 상단 업무 카드(`RoomSheet`)를 분리·병존시키되 작은 화면 높이에서 채팅 공간 확보 |
| **카카오톡** | 독립 대화 목록과 읽지 않은 메시지 표시 | 메시지함 목록·읽지 않음 배지(`99+` 상한)와 일행 관리(`/rides`)의 목적을 구분해 병행 유지 |
| **네이버 지도·카카오맵** | PC 대시보드/지도 화면의 분할 레이아웃 | 넓은 화면에서는 상단 내비게이션과 작업·지도 2열 구성을 쓰고, 모바일 하단 탭 바는 숨김 |

---

## 3. 도출된 디자인·UX 원칙

1. **Deep Green / Mist 일관성과 대비 확보:** 브랜드 주색 `#145a46`과 배경 `#f7f8f5`를 유지하되 주색 위 텍스트는 `#ffffff`, 보조 본문·라벨 토큰은 충분한 명도 대비를 확보한다.
2. **현재 할 일 최우선(Action First):** 반복 설명이나 장식 카드를 줄이고, 진행 중인 동승·미납·확인 대기 같은 긴급 행동을 가장 먼저 노출한다.
3. **한 화면 하나의 핵심 숫자:** 결제·정산 화면에서는 `내 분담금`과 `절약액`을 최우선 계층으로 두고, 수금 진행률과 개별 정산 내역을 중복 없이 아래에 배치한다.
4. **역할·기기별 최적 레이아웃:** PC 학생 화면은 상단 내비게이션, 모바일은 5열 하단 탭 바, 관리자는 좌측 사이드바와 검색 가능한 목록 중심 포털로 분리한다.
5. **절제된 시각 위계와 한국어 오류 복구:** 불필요한 중첩 카드·장식 배지·죽은 CSS를 정리하고, 네트워크 실패와 입력 제약에서도 사용자가 다음 행동을 즉시 선택할 수 있게 한다.

---

## 4. 화면별 개선 내역

### 4.1 공통 디자인 시스템 · 라우팅 · 오류 복구

- **SPA 직접 라우팅(`WebConfig.java`, `SpaForwardingTest.java`):** `/history`, `/rides`, `/me`, `/request/school`, `/request/home`, `/parties/{id}`, `/admin`, `/boarding-cases`, `/presenter`, `/find`, `/app/find`, `/messages`, `/app/messages`, `/messages/{id}`, `/app/messages/{id}`, `/app/history`, `/app/trips`, `/app/trips/{id}`, `/app/account`, `/app/account/payments`, `/payments`, `/app/account/cases`, `/demo` 총 23개 활성 경로를 `/index.html`로 포워드해 직접 진입 및 새로고침 404를 해소했다.
- **토큰 및 대비(`tokens.css`, `styles.css`, `layouts.css`):** `--color-text-muted: #52635a`, `--faint: #64746b`, `--off-ink: #57675f`, `--shadow-elevated`를 정리했고, `.rail li.me .pin`, `.mk.me`, `.mk.time`, `.road-car`, `.driver li span`의 전경색을 `#ffffff`로 수정했다. `.toast svg` 색상을 `#7ee0ba`로 맞추고 `.toasts`의 `z-index`를 `90`으로 높여 `.driver`(80) 위에서도 선명하게 보이도록 개선했다.
- **데드 CSS 정리 및 반응형 보정:** 미사용 선택자를 제거하고 `@media (min-width: 1024px)` 블록 괄호 닫힘 누락(`layouts.css`)을 수정했다. 데스크톱에서는 빈 `.dock`을 DOM에서 제거(`!wide && tabBar`)하고 `.tabbar` 5열 그리드를 통합했다. 작은 화면(`max-height: 700px`)에서 `.chat-task` 최대 높이를 제한해 대화 영역 침범을 완화했다.
- **네트워크 복구 및 인증 정리(`api.js`, `App.jsx`, `StudentLayout.jsx`):**
  - `fetch` 네트워크 예외를 한국어 `ApiError(0, 'NETWORK_ERROR', '네트워크 연결이 불안정합니다. 잠시 후 다시 시도해 주세요.')`로 변환했다.
  - `loadBoot`에 `[150, 300]` ms 재시도를 추가하고 실패 카드 재시도 버튼을 직접 연결했다.
  - `signOut`이 `try/catch`로 API 응답을 기다린 뒤 `state`와 `me`를 초기화하고 루트로 이동하도록 정비했다.
  - PC 상단 바(`StudentLayout`)에 `me.admin`일 때만 `관리자` 진입 링크를 추가했고, `/me`와 `/rides` PC 제목을 `page-heading`으로 정리했으며, `/me` 메뉴를 `계정 · 결제`, `이용 · 알림`, `운영 · 시연` 섹션으로 구조화했다.
  - 데스크톱 공개 랜딩(`.public-shell`)의 스크롤을 허용하고 우측 로그인 카드 상단 중복 브랜드 헤더를 숨겼다.

### 4.2 홈 (`Home.jsx`)

- `진행 중인 이용` 카드를 `.home-grid` 상단으로 올려 탑승 확인·후불 확인(`CONFIRM_AND_PAY`)·수금 대기 행동을 가장 먼저 인지할 수 있게 배치했다.
- 이미 `parties` 또는 `settledParty` 기록이 있는 이용자에게는 3단계 온보딩(`이용 방법`) 카드를 숨겨 반복 이용 화면 밀도를 개선했다.

### 4.3 동승 요청 및 지도 (`RequestFlow.jsx`, `MapPicker.jsx`, `RouteComparison.jsx`, `RequestStatus.jsx`, `Board.jsx`, `DemoBar.jsx`)

- 지도 핀·폴리라인 잔여 노란색을 `#145a46`으로 통일하고 `RouteComparison` 범례 문구를 `"초록 실선은 같이 가는 길..."`로 일치시켰다.
- 결제수단이 미등록(`paymentMissing`)된 경우 비활성 막힘 대신 `'결제수단 등록하고 이어서 요청'` 버튼으로 즉시 이동·복귀할 수 있게 개선했다.
- 도착 기준(`ARRIVE`) 선택 시 여유 도착 가능성에 대한 보조 안내 문구를 추가했다.
- `RequestStatus`, `Board`, `DemoBar`에서 v2 선결제 잔여 표현을 v3 확인형 후불 기준 문구로 분기·정리했다.

### 4.4 일행방 · 기사님 안내 · 이용 상세 · 이용 내역 (`PartyScreens.jsx`, `PartyShell.jsx`, `TripDetailPage.jsx`, `History.jsx`, `RideHistoryService.java`, `ApiController.java`, `format.js`, `ui.jsx`)

- `FairnessNote`에 `postpaid` 분기를 추가해 하차 후 실제 요금을 혼자 탈 때 예상 요금 비율대로 나누는 v3 정산 원칙을 명확히 설명했다.
- `DriverView` 하단에 명시적 닫기 CTA(`'확인했어요 · 탑승 화면으로'`)를 추가하고 `.toasts` 레이어 충돌을 해소했다.
- **이용 내역 백엔드·공통 계산 정합화 (`RideHistoryService.java`, `ApiController.java`, `format.js`):** 기존 `paid` 계약을 유지하면서 `Entry`·`RequestView`에 `myShare`(확정 본인 분담금)와 `payer`(결제자 여부)를 추가했고, `historyMyShare`와 `savedTotal`로 결제자도 본인 분담금 기준 절약액을 정확히 합산·표시하도록 수정했다.
- **이용 내역 정보 구조 및 레이아웃 개편 (`History.jsx`, `App.jsx`, `layouts.css`, `styles.css`):**
  - 상단 요약 배너(`같이 타서 아낀 돈`, `진행 중`·`완료·종료` 건수)와 상태 필터 탭(`전체`·`진행 중`·`지난 이용`)을 도입하고, `진행 중` 항목이 `지난 이용`보다 먼저 노출·선택되도록 우선순위를 변경했다.
  - 데스크톱(`>= 1024px`)에서는 `.item.sel` 좌우 폭 점프를 제거하고 좌측 목록 + 우측 고정 상세(`.history-detail-pane`) 2단 마스터-디테일로 전환했으며, 모바일 펼침 상태에서는 카드 상단 정보를 중복 출력하지 않고 `.history-money-hero`부터 표시하도록 정리했다.
  - 중복 요금 표(`FinancialDetails` 이중 출력) 대신 단일 `내 분담금`·`실제 택시비 (현장 결제)`·`각자 분담금`으로 압축하고, 정상 승인 단일 전표(`PaymentVoucherBox`)는 1줄 요약 + `결제 전표 보기` 접힘으로 정리했다(환불·보류·미승인 건은 자동 펼침).
  - 정산 전(`settlement == null`) 단계에서는 결제자도 동승자와 동일하게 `정산 전`으로 표시하며, 아코디언 내 중첩 채팅 대신 `일행·정산 화면 열기` / `일행 상세 보기` 및 `메시지 열기` 바로가기를 배치했다. 후불 빈 주문(`BOARDING_*`)에서는 모순된 `"선결제는 조사 중 보류돼요"` 문구를 실제 `PREPAY`/`refundHeld` 주문이 있을 때만 표시하도록 제한했다.
  - `.badge.warn`을 경고 시맨틱 토큰(`--color-warning-soft`, `--color-warning`)으로 분리해 진행·확인 필요 상태가 완료(초록) 배지와 명확히 구분되도록 했다.

### 4.5 결제 및 정산 (`SettlementPage.jsx`, `SettleScreens.jsx`)

- `SettlementPage`에서 `FinancialDetails`에 `hideCollection`을 전달해 동일 `PostpaidCollection` 표가 두 번 렌더링되던 중복 결함을 제거했다.
- `PostpaidSettlementPanel`에 토스형 금액 히어로 카드(`.money-hero`, 내 분담금·단독 대비 절약액)와 결제자 수금 진행 바(`.money-progress`)를 추가했다.
- `ReceiptField`에서 잘못된 형식·용량의 파일을 골랐을 때 `<input type="file">` 값을 초기화해 같은 파일명이나 재선택 시 마찰이 없도록 개선했고, `PayerInput` 비활성 제출 버튼 옆 인라인 사유 안내를 추가했다.

### 4.6 관리자 포털 (`AdminLayout.jsx`, `ReviewScreens.jsx`)

- 각 탭에 반복 노출되던 모의 운영 장부 안내 `<Say>`를 `AdminLayout` 상단 1곳으로 올렸다.
- 6개 탭(`노쇼 검토`, `요금·운행`, `신고·제한`, `미정산`, `승차 지점`, `처리 기록`)마다 `ADMIN_TAB_DESC` 설명을 추가하고, `AdminList`에 클라이언트 검색 필터(`#admin-filter`)를 도입해 대량 항목 탐색 효율을 높였다.

---

## 5. 의도적으로 유지한 항목 및 정책·디자인 경계

| 구분 | 유지한 항목 | 유지 사유 및 정책 경계 |
|---|---|---|
| **핵심 매칭·정산 로직** | `matching-engine/` 우회 15분 이하·10% 이상 절약 규칙, `SettlementCalculator`, v3 후불 1회 결제·이의·자동 확정 정책 | 작업 원칙상 매칭·요금 분담·노쇼·정산 비즈니스 규칙은 UI 개선 범위에서 임의 변경하지 않음 |
| **내 일행 vs 메시지 분리** | `/rides`(내 일행 상태·합승)와 `/messages`(독립 대화 목록·채팅방) 병행 유지 | 상태 관리·탑승 수행과 실시간 채팅 목록의 사용자 과업이 달라 기능 삭제 없이 각 화면 목적과 위계를 선명화함 |
| **모의 정산·모의 장부 고지** | 학생 정산 및 관리자 포털 상단의 모의 결제·모의 운영 장부 안내 | 실제 금융기관·지급대행 연동이 아닌 환경에서 실제 송금으로 오인하지 않게 정책상 명시 고지를 유지하되 중복 노출만 제거 |
| **단일 지도 경로 구조** | `/request/home` · `/request/school` 통합 요청 흐름 유지 | 별도 `/request/campus` 신설 대신 등교·하교 토글과 방향 파라미터 구조를 유지해 라우팅 분산을 방지 |

---

## 6. 전후 비교 근거 및 10개 해상도 반응형 검증

### 6.1 주요 전후 비교

| 영역 | 개선 전(사전 감사) | 개선 후(`feat/ui-ux-polish`) |
|---|---|---|
| **SPA 직접 진입·새로고침** | `/app/find`, `/app/messages`, `/demo` 등 다수 경로 직접 로드 시 404 | `WebConfig.java` 23개 경로 포워딩 + `SpaForwardingTest` 23/23 및 E2E 7경로 200 OK 확인 |
| **주색 위 텍스트 대비** | `#172521` 잉크가 `#145a46` 위에 얹혀 약 1.8:1 저대비 | `#ffffff` 전경 적용 및 보조 텍스트·라벨 토큰 대비 상향 |
| **토스트 알림 vs 기사님 안내** | `.driver`(`z-index: 80`)가 `.toasts`(`70`)를 가려 알림 미표시 | `.toasts` `z-index: 90` 상향으로 오버레이 위 복사·호출 토스트 즉시 확인 |
| **정산 화면 정보 위계** | 결제자 수금 목록 중복, 일반 항목과 금액 숫자 비중 동일 | `.money-hero` 단일 핵심 금액 + `.money-progress` + 중복 수금 표 제거 |
| **홈 정보 우선순위** | 진행 중인 이용보다 3단계 소개 카드가 앞서거나 반복 노출 | 진행 중인 이용 최상단 우선 노출 + 기존 이용자 온보딩 카드 숨김 |
| **모바일 하단 탭·데스크톱 도크** | 데스크톱에서도 빈 `.dock` 렌더, CSS 정의 혼재 | 데스크톱 `.dock` 미출력, 모바일 5열 `.tabbar` 균등 정렬 |
| **이용 내역 (`/history`)** | 금액 3~5회 중복, 결제자 전액(`partyPaid`) 오인·절약액 누락, PC 1단 `.item.sel` 폭 점프, 완료 우선 펼침 | PC 2단 마스터-디테일·모바일 카드, `myShare` 기반 분담금·절약액 표시, `.badge.warn` 구분, 일행·메시지 바로가기 제공 |

### 6.2 10개 해상도 자동 E2E 및 시각 검수

`frontend/e2e/ui-quality.spec.js`로 공개 랜딩(`/`)과 인증 후 학생·관리자 7개 핵심 경로(`/`, `/app/find`, `/request/home`, `/app/account/payments`, `/app/messages`, `/history`, `/admin`)를 총 **10개 해상도**(80개 뷰포트 조합)에서 순회 점검한 결과 `scrollWidth <= innerWidth` 조건을 모두 통과하고 `console.error` · `pageerror` 0건을 기록했다.

| 해상도 (px) | 구분 | 검증 범위 | 가로 넘침 |
|---|---|---|---|
| `320×568` | 소형 모바일 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `360×800` | 일반 Android | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `390×844` | 표준 iOS | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `430×932` | 대형 모바일 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `768×1024` | 태블릿 세로 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `1024×768` | 소형 데스크톱/태블릿 가로 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `1280×800` | 표준 노트북 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `1440×900` | 와이드 노트북 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `1920×1080` | FHD 데스크톱 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |
| `2560×1440` | QHD 데스크톱 | 랜딩 + 학생·관리자 7개 경로 | 없음 (`0px`) |

직접 육안 검수한 대표 스크린샷(`output/playwright/ui-audit/`):
- `landing-desktop-1280.png`: 2열 랜딩 레이아웃과 우측 단일 로그인 카드(중복 브랜드 제거) 확인
- `home-desktop-1280.png`: 상단 6메뉴 네비게이션·관리자 진입·여백 균형 및 빈 하단 도크 제거 확인
- `home-mobile-390.png`: 5열 하단 탭(`홈`, `내 일행`, `메시지`, `이용 내역`, `내 정보`) 줄바꿈 없는 1행 정렬 확인
- `admin-desktop-1440.png`: 240px 좌측 사이드바, 단일 모의 장부 안내, 탭별 설명 문구 확인

---

## 7. 수정 및 신규 파일 목록

- **백엔드 및 테스트 (5개)**
  - 수정: `server/src/main/java/com/campustaximate/server/web/WebConfig.java`, `server/src/main/java/com/campustaximate/server/service/RideHistoryService.java`, `server/src/main/java/com/campustaximate/server/web/ApiController.java`, `server/src/test/java/com/campustaximate/server/PostpaidSettlementCalculationTest.java`
  - 신규: `server/src/test/java/com/campustaximate/server/web/SpaForwardingTest.java`
- **프런트엔드 스타일 (4개)**
  - 수정: `frontend/src/styles/tokens.css`, `frontend/src/styles/layouts.css`, `frontend/src/styles.css`, `frontend/src/reviews.css`
- **프런트엔드 UI · 화면 · 상태 · 유틸 (20개)**
  - 수정: `frontend/src/api.js`, `frontend/src/App.jsx`, `frontend/src/format.js`, `frontend/src/ui.jsx`, `frontend/src/Home.jsx`, `frontend/src/RequestFlow.jsx`, `frontend/src/RequestStatus.jsx`, `frontend/src/Board.jsx`, `frontend/src/DemoBar.jsx`, `frontend/src/MapPicker.jsx`, `frontend/src/RouteComparison.jsx`, `frontend/src/PartyScreens.jsx`, `frontend/src/PartyShell.jsx`, `frontend/src/History.jsx`, `frontend/src/ReviewScreens.jsx`, `frontend/src/SettleScreens.jsx`, `frontend/src/layouts/StudentLayout.jsx`, `frontend/src/layouts/AdminLayout.jsx`, `frontend/src/pages/SettlementPage.jsx`, `frontend/src/pages/TripDetailPage.jsx`
- **프런트엔드 단위 · E2E 테스트 (6개 변경 + 기존 연결 테스트 재검증)**
  - 수정: `frontend/src/format.test.js`, `frontend/src/Home.test.jsx`, `frontend/src/pages/PaymentMethodsAndConversations.test.jsx`, `frontend/src/pages/PostpaidSettlement.test.jsx`
  - 신규: `frontend/src/History.test.jsx`, `frontend/e2e/ui-quality.spec.js`
  - (기존 `frontend/src/appConnection.test.jsx`는 `loadBoot` 재시도 지연시간 `[150, 300]` 기준에서 그대로 검증 통과)
- **문서 (1개)**
  - 신규: `docs/05-submission/ui-ux-quality-report.md`

---

## 8. 자동 테스트 및 라이브 브라우저 검증 결과

모든 검증은 최종 코드 수정(`History.jsx` · `layouts.css` 최종 다듬기 포함) **이후** 다시 실행해 확인했다.

| 검증 항목 | 실행 명령/환경 | 결과 |
|---|---|---|
| **프런트엔드 단위·통합 테스트** | `cd frontend && npm test -- --run` | **24개 파일 / 206개 테스트 전부 통과 (0 실패)** |
| **프로덕션 번들 빌드** | `cd frontend && npm run build` & Docker stage `vite build` | `index-BVt6Ow0P.css` `132.10 kB` · `index-DeNCKIjY.js` `383.58 kB` **CSS 문법 경고 0건 빌드 성공** |
| **백엔드 모듈 테스트·패키징** | `./mvnw package` | **`BUILD SUCCESS`**: `matching-engine` **77건 통과 (0 실패)**, `server` **589건 중 활성 456건 통과 / MySQL 프로필 미활성 133건 스킵 (0 실패)** (`SpaForwardingTest` 23건 및 `/api/history` 분담금·결제자 회귀 테스트 포함) |
| **Playwright E2E 및 10해상도 감사** | `TAXIMATE_E2E_PORT=18080 npx playwright test --reporter=list` | **8건 통과 (`[pc]` 5건 + `[phone]` 3건), `[phone]` 프로젝트 중복 스킵 2건, 0 실패** (`flow.spec.js` 양 플랫폼 통과 + `ui-quality.spec.js` 통과) |
| **Docker Compose 기동 및 헬스체크** | `docker compose up --build -d` (`.env` 키 포함) | `campus-taximate-app-1` (`healthy`, `8080`), `campus-taximate-mysql-1` (`healthy`, `3307`) 정상 가동 |
| **라이브 브라우저 실사 검증** | `http://127.0.0.1:8080` 데스크톱(`1440×900`·`1280×800`)·모바일(`390×844`) 실사용 점검 | SPA 5경로 200 OK·`/admin`·홈·정산 실검증 및 `/history` 개편 실검증(`GET /api/history` `myShare`·`payer`, 결제자 `준호` 절약액 `7,283원`·본인 분담금 `6,417원`, PC `410px + 642px` 2단, 모바일 0 가로 넘침·`.badge.warn` 구분·단일 전표 토글·일행/메시지 이동) 후 발견된 세부 3건(`정산 전` 일관성·선택 모서리·보조색) 보완 및 전체 자동 테스트·E2E·Docker 재빌드 완료 |

---

## 9. 잔여 한계 및 후속 과제

라이브 Docker Compose 및 실브라우저 검증에서 다음 4가지 잔여 한계가 관찰되었으며, 추후 개선 과제로 기록한다.

1. **카카오 지도 JS SDK 외부 도메인 인증 제약 (`401 / net::ERR_BLOCKED_BY_ORB`)**
   - 서버 측 `KAKAO_REST_KEY` 기반 경로·요금 미리보기(`POST /api/requests/preview`)는 정상 동작했으나, 브라우저의 `https://dapi.kakao.com/v2/maps/sdk.js` 요청이 `127.0.0.1:8080` 환경에서 HTTP `401` / `ERR_BLOCKED_BY_ORB`로 차단되었다.
   - 좁은 모바일 뷰에서는 격자형 대체 핀 화면이 나타났으나 데스크톱 넓은 화면에서는 우측 지도 영역이 빈 배경으로 남았다. 카카오 개발자 콘솔 웹 플랫폼 도메인(`http://localhost:8080`, `http://127.0.0.1:8080`) 등록 확인 및 SDK 로드 실패 시 데스크톱에서도 대체 지도 안내가 즉시 표시되도록 보완이 필요하다.
2. **무탑승 정산 시나리오(`0 / 0명`)에서의 빈 진행률 영역**
   - `/demo` 시나리오 중 동승 인원 탑승 확정이 0명인 `/parties/1` 상태에서 결제자 화면이 `0 / 0명 확인·결제 완료` 문구만 표시하고 `.money-progress` 막대는 생략되는 경계 사례가 확인되었다. 무탑승·미확정 파티 전용 안내 상태를 추가하면 더 명확해진다.
3. **고해상도/DPI 배율 환경의 뷰포트 임계값**
   - Windows DPI 배율과 스크롤바가 적용된 내장 웹뷰(`1280×800` 지정 시 실측 CSS 폭 `956px~971px`)에서는 초기 로드 시 `< 1024px` 단일 열이 먼저 그려졌다. 표준 Chromium CSS 뷰포트(`1280×800`)에서는 2열이 정상 렌더링되지만 `960px~1024px` 중간 구간 태블릿·배율 화면용 브레이크포인트 미세 조정 여지가 있다.
4. **로컬 호스트 접속 차이 및 실시간 MySQL E2E 범위**
   - 검증 중 내장 브라우저는 `http://localhost:8080` 첫 연결에서 `ERR_CONNECTION_RESET`을 보였으나 `http://127.0.0.1:8080`에서는 전 구간 HTTP 200으로 안정 동작했다. 또한 본 최종 스위트에서는 MySQL 프로파일 133개 테스트를 직접 재실행하지 않았으므로(Docker 실기동 및 Playwright JAR H2 E2E로 보완), MySQL 정산 회귀 검증은 기존 MySQL 프로파일 실행 이력과 별도 구분해 관리한다.

---

## 10. 비판적 최종 평가

이번 개선으로 캠퍼스 택시메이트는 초기 프로토타입에서 드러나던 **직접 URL 새로고침 404, 주색 위 저대비 텍스트, 오버레이 위 토스트 가림, 정산 목록 중복, 데스크톱 빈 하단 도크, 반복 이용자 대상 과잉 안내** 문제를 구조적으로 해소했다. 특히 v3 확인형 후불 정산 문구와 토스형 금액 히어로 카드·수금 진행률이 일관되게 자리 잡아, 실제 이용 흐름만 놓고 보면 출시 가능한 상용 서비스 수준에 훨씬 가까워졌다.

다만 아직 **실제 지도가 그려져야 할 데스크톱 요청 화면 우측 영역이 카카오 JS 키 도메인 제한 상황에서 빈 화면으로 남는 문제**는 첫인상과 모빌리티 서비스 완성도에 직접적인 감점 요인이다. 또한 `ReviewScreens.jsx`의 정적·동적 중복 import로 인한 Vite 청크 분리 경고, 일부 테스트의 `act(...)` 경고처럼 기능에는 영향이 없으나 기술 부채로 남은 부분도 존재한다. 다음 단계에서는 **SDK 차단/오류 시 데스크톱 대체 지도 폴백 전환**과 **운영 도메인 키 일치 검증**을 우선 처리하면 시각·기능 완성도의 마지막 공백까지 메울 수 있다.

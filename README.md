# 캠퍼스 택시메이트

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

집과 학교를 오가는 대학생을 출발 전에 같은 방향 일행으로 자동 매칭하고, 택시비를 각자의 단독 요금 비율로 나누는 서비스입니다. 결제자는 일반 택시를 잡아 택시비를 내고, 다른 일행원은 앱에서 확정 분담금을 선결제합니다(S43). 확정 분담금은 카카오 견적(보정 없음, 실험 C16)에 적립 여유분(1%)을 반영한 금액이고 최종 금액이라, 요금이 더 나와도 나중에 더 내지 않습니다. 실제 요금·영수증 입력 뒤 24시간 이의 기간이 지나면 결제자는 실제 요금에서 자기 몫을 뺀 금액을 받고, 차이는 정산 적립금 장부가 맡습니다. 2~3명 일행은 모집 마감 회차까지 더 모아서 확정합니다(S42). 결제자에게 줄 돈은 명세 S13에 따라 **모의 지급 장부에 기록하며 실제 송금하지 않습니다.** 현재 서비스는 이 지급 제약을 포함한 캡스톤 시연 구현입니다.

## 주요 기능

- 현재 제공하는 매칭은 공통 승차 지점에서 함께 타고 각자 목적지에서 내리는 방식입니다. 과거의 순차 승차 유형은 새 요청에 제공하지 않습니다
- 결제자용 기사님 안내 화면, 정산마다 쌓이는 예상 요금 정확도
- 일행 채팅: 자주 쓰는 문구, 신고. 정산이 끝나거나 출발 후 24시간이 지나면 쓰기를 닫고 7일 뒤 삭제
- 5분 슬롯 매칭. 지연 15분, 요금 10% 조건을 카카오 길찾기로 확인한 조합만 확정
- 단독 요금 비율 정산. 단독 요금은 요청 때 보여 준 값으로 고정
- 노쇼 처리, 결제자 넘김, 남은 일행 재계산
- 일행 정보 탭에서 탑승 확인, 내 정보 → 내 탑승 확인·소명에서 조사 확인과 24시간 소명 제출
- 관리자 화면(`/admin`): 노쇼 판정, 요금·운행 검토, 신고·요청 제한, 미정산 확인, 승차 지점 등록·수정, 처리 기록
- FR-09 신고·다시 매칭 안 함, 서로 다른 일행 3곳 신고 시 7일 요청 제한, 추가 결제 미완료 시 새 요청 제한
- 휴대폰 화면과 PC 화면(폭 1024px 이상에서 왼쪽 작업, 오른쪽 지도)
- PC·휴대폰 화면 E2E와 브라우저 호환성 검사 설정 제공. `BSET=edge|firefox|safari npm run e2e:compat`

## 구성

| 폴더 | 내용 |
|---|---|
| `matching-engine/` | 매칭 추정과 정산 계산. DB, 스프링, 카카오에 의존하지 않는 순수 Java |
| `server/` | Spring Boot API. 인증, 슬롯 스케줄러, 일행, 정산, 실시간 신호, 메일 재시도 |
| `frontend/` | React 화면. 빌드하면 `server`의 정적 파일로 들어간다 |
| `docs/openapi.json` | API 명세 |
| `experiments/` | 현재 엔진으로 실행하는 재현 실험과 실제 요금 수집·분석 |

## 완성 작업과 제출

[완료 관리표](docs/completion-status.md)에서 교수님 피드백, 팀원 제안, 추가 결함과 검증 근거를 구분합니다. 탑승번호는 **팀원 제안**이며, [S45 추가 명세](docs/boarding-and-completion-spec.md)에 도착·실제 탑승·운행 시작과 번호 입력 기한을 정의합니다. 코드만으로 실제 요금 오차나 상용 운영 가능성이 입증되지는 않습니다.

팀원 실제 탑승 기록 수집은 하지 않기로 결정했고(11/19, [완료 관리표](docs/completion-status.md)), 요금 오차는 공개 실제 운행 기록(TIMS 2025 등)으로 측정하며 일반 택시 영수증 실측과 구분해 설명합니다. 실험은 캐시를 우선 사용하고 신규 카카오 호출은 최대 100회로 제한합니다. 원본·키는 비공개로 유지합니다.

검토용 소스 묶음과 파일별 SHA-256 목록은 Python 3.11 이상에서 만듭니다. 작업 중인 파일도 포함되므로 결과의 `working_tree_dirty`와 파일 해시를 확인하세요. PPT·PDF·시연 영상은 별도 제출 자료입니다.

```powershell
python scripts/release_bundle.py
```

결과는 `.cache/release/campus-taximate-source.zip`과 `.zip.sha256`입니다. 비밀키·업로드·원본 탑승 자료·실행 캐시는 제외합니다.

## 시연 실행

시연도 운영과 같은 MySQL로 돌립니다. Java 21, Node 20, MySQL 8이 필요합니다. `.env.example`을 복사해 `.env`를 만들고 카카오 키를 넣습니다. `KAKAO_REST_KEY`가 없으면 모의 경로로 돕니다.

`demo` 프로필은 `app.payment.simulated=true`로 외부 결제가 없는 모의 결제기를 사용합니다. 이때 `TOSS_SECRET_KEY`는 비워 둡니다. 토스 테스트 결제창으로 시연하려면 테스트용 서버·클라이언트 키를 설정하고 `--app.payment.simulated=false`로 실행하세요. 키가 있다는 이유로 모의·실제 결제기를 자동 전환하지 않습니다.

1. 화면을 빌드해 서버에 넣고 jar를 만듭니다.

```bash
cd frontend && npm ci && npm run build && cd ..
./mvnw package -DskipTests
```

2. 설치된 MySQL 8 프로그램으로 이 프로젝트 전용 MySQL을 관리자 권한 없이 3307 포트에 띄웁니다. 처음 한 번은 DB와 사용자를 만들고, 이미 떠 있으면 그대로 둡니다. 기존 MySQL 서비스와 데이터는 건드리지 않습니다.

```powershell
./scripts/local-mysql.ps1
```

3. `demo,mysql` 프로필로 서버를 켭니다.

```bash
set -a; . ./.env; set +a
TOSS_SECRET_KEY= SPRING_PROFILES_ACTIVE=demo,mysql java -jar server/target/server-0.1.0.jar
```

`http://localhost:8080`에서 `demo-f1@daejin.ac.kr`, 비밀번호 `demo1234`로 로그인합니다. 새 계정은 학교 메일 코드로 가입을 인증한 뒤 비밀번호를 정하며, 시연 계정은 코드가 자동으로 들어갑니다. 요청을 만든 뒤 상단의 시연 메뉴에서 일행 만들기를 누르면 일행이 정해집니다.

Flyway가 DB 구조를 만들고, 시연은 시계를 앞으로 돌리므로 실행할 때마다 DB를 비우고 시작합니다. 비우는 것은 시연 모드이면서 DB 주소가 이 컴퓨터일 때뿐입니다. `demo` 프로필만 켜면 메모리 DB(H2)로 돌며, 이는 자동 테스트(E2E·부하 시험)용입니다.

API 문서는 시연 실행 중 `http://localhost:8080/swagger-ui.html`에서 볼 수 있습니다.

## 운영 실행

`.env`에 `.env.example`의 값을 모두 채운 뒤 실행합니다. `ACCOUNT_KEY`, `KAKAO_REST_KEY`, `MAIL_HOST`, `TOSS_SECRET_KEY`가 없으면 서버가 시작을 거부합니다. 화면 빌드에는 짝이 맞는 `VITE_TOSS_CLIENT_KEY`가 필요합니다. 모의 결제는 실제 `demo` 프로필과 명시적 모의 결제 설정이 모두 있어야 허용되며, `app.demo.enabled=true`만으로 운영에서 켤 수 없습니다. DB 구조는 Flyway가 만들고, 시작할 때 엔티티와 일치하는지 검사합니다.

```bash
docker compose up -d --build
```

`/actuator/health`로 상태를 확인합니다. 로그를 파일로 남기려면 `SPRING_PROFILES_ACTIVE=logfile`과 `LOG_DIR`을 넣습니다.

## 개발

Windows에서는 JDK 21과 Maven을 지정해 프런트·서버·실험 도구를 한 번에 검사할 수 있습니다. `-WithMysql`은 기존 데이터와 분리한 검증 DB의 `DB_URL`, `MYSQL_TEST_URL`, `DB_USER`, `DB_PASSWORD`와 별도 `taximate_migration` DB를 가리키는 `MYSQL_MIGRATION_TEST_URL`을 요구합니다. 마이그레이션 테스트는 해당 별도 DB를 비우고 신규 설치와 업그레이드를 검사합니다.

```powershell
./scripts/verify.ps1 -JavaHome 'JDK 21 설치 경로' -Maven 'mvn 실행 파일 경로'
```

```bash
./mvnw verify                 # 서버와 엔진 테스트
cd frontend
npm run dev                   # 5173 포트, API는 8080으로 전달
npm run lint
npm test
npm run e2e                   # 서버 jar와 화면 빌드 뒤, PC와 휴대폰 화면으로 흐름 확인
```

`MYSQL_TEST_URL`을 넣으면 `MigrationSchemaTest`가 실제 MySQL에서 돕니다. CI는 매번 MySQL 8.4에서 이 검사를 합니다.

브라우저 검증은 빌드된 JAR로 전용 시연 서버를 띄웁니다. 기존 서버가 8080을 사용하면 `TAXIMATE_E2E_PORT`를 별도로 지정하세요. `TAXIMATE_E2E_CHROME=1`은 설치된 Chrome을 사용하고, `TAXIMATE_E2E_VIDEO=1`은 브라우저 시연 영상도 저장합니다.

## 보안

- API는 기본 거부. 공개 경로는 `AccessInterceptor`에 있는 목록뿐이고, 테스트가 모든 경로를 훑어 확인합니다
- 비밀번호는 PBKDF2 해시로, 인증 코드와 유지 토큰은 해시로, 계좌번호는 AES-GCM으로 저장합니다
- 코드 실패는 코드별 5회, 하루 20회로 막고, 인증 경로는 IP별 분당 30회로 제한합니다
- 코드 발급·소비와 로그인 실패 횟수 갱신은 DB 행 잠금으로 보호합니다. 로그인 세션도 철회 가능한 토큰을 매 요청 검사해 모든 기기에서 로그아웃한 뒤 쿠키를 생략해도 접근할 수 없습니다
- 다른 사이트의 쓰기 요청은 Origin 검사로 막고, 결제 사진은 파일 앞부분으로 형식을 확인합니다

## 아직 확인하지 못한 것

- 심야, 시외 할증에서의 카카오 요금 값
- 길찾기 값 저장 허용 여부
- 초기 거점과 지점 좌표는 시연용 샘플입니다

## 알림

확정, 미성사, 일행 변경과 취소, 결제자 변경, 승차 10분 전 안내는 메일과 웹 푸시로 갑니다. 푸시는 내 정보에서 알림 받기를 누른 기기에만 가고, `PUSH_PUBLIC_KEY`와 `PUSH_PRIVATE_KEY`가 없으면 꺼집니다. 시연 프로필은 실행마다 새 키를 만듭니다.

## 시연 범위와 운영 제약

- 결제자 실제 지급은 없습니다. `PayoutLedger`와 화면의 ‘실제 송금 없음’ 표시는 모의 장부이며, 실제 현금 지급에는 별도 지급대행 계약·연동이 필요합니다
- 요청 제한과 실시간 신호는 서버 한 대 기준입니다
- 자동 노쇼 확정은 24시간 뒤 부재 표시만 있고 소명이 없는 경우에만 합니다. 그 밖에는 관리자 화면에서 자료와 근거를 확인해 처리합니다

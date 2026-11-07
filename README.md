# 캠퍼스 택시메이트

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

집과 학교를 오가는 대학생을 출발 전에 같은 방향 일행으로 자동 매칭하고, 택시비를 각자의 단독 요금 비율로 나누는 서비스입니다. 앱은 호출과 결제를 대신하지 않습니다. 결제자가 일반 택시를 잡고 결제하면 나머지가 은행 앱으로 보내고, 앱은 금액 계산과 보냄, 받음 표시를 맡습니다.

## 주요 기능

- 하교: 같은 거점에서 타고 각자 다른 지점에서 내림. 등교: 먼 사람부터 차례로 타고 학교에서 함께 내림
- 결제자용 기사님 안내 화면, 정산마다 쌓이는 예상 요금 정확도
- 5분 슬롯 매칭. 지연 15분, 요금 10% 조건을 카카오 길찾기로 확인한 조합만 확정
- 단독 요금 비율 정산. 단독 요금은 요청 때 보여 준 값으로 고정
- 노쇼 처리, 결제자 넘김, 남은 일행 재계산
- 휴대폰 화면과 PC 화면(폭 1024px 이상에서 왼쪽 작업, 오른쪽 지도)

## 구성

| 폴더 | 내용 |
|---|---|
| `matching-engine/` | 매칭 추정과 정산 계산. DB, 스프링, 카카오에 의존하지 않는 순수 Java |
| `server/` | Spring Boot API. 인증, 슬롯 스케줄러, 일행, 정산, 실시간 신호, 메일 재시도 |
| `frontend/` | React 화면. 빌드하면 `server`의 정적 파일로 들어간다 |
| `docs/openapi.json` | API 명세 |

## 시연 실행

MySQL 없이 돌아가고 재시작하면 데이터가 사라집니다. Java 21과 Node 20이 필요합니다. `.env.example`을 복사해 `.env`를 만들고 카카오 키를 넣습니다. `KAKAO_REST_KEY`가 없으면 모의 경로로 돕니다.

```bash
cd frontend && npm ci && npm run build && cd ..
./mvnw package -DskipTests
set -a; . ./.env; set +a
SPRING_PROFILES_ACTIVE=demo java -jar server/target/server-0.1.0.jar
```

`http://localhost:8080`에서 `demo-f1@daejin.ac.kr`로 로그인합니다. 시연 계정은 코드가 자동으로 들어갑니다. 요청을 만든 뒤 상단의 시연 메뉴에서 일행 만들기를 누르면 일행이 정해집니다.

### MySQL로 시연하기

설치된 MySQL 8 프로그램으로 이 프로젝트 전용 MySQL을 관리자 권한 없이 3307 포트에 띄웁니다. 기존 MySQL 서비스와 데이터는 건드리지 않습니다.

```powershell
./scripts/local-mysql.ps1
```

```bash
set -a; . ./.env; set +a
SPRING_PROFILES_ACTIVE=demo,mysql java -jar server/target/server-0.1.0.jar
```

Flyway가 DB 구조를 만들고, 시연은 시계를 앞으로 돌리므로 실행할 때마다 DB를 비우고 시작합니다. 비우는 것은 시연 모드이면서 DB 주소가 이 컴퓨터일 때뿐입니다.

API 문서는 시연 실행 중 `http://localhost:8080/swagger-ui.html`에서 볼 수 있습니다.

## 운영 실행

`.env`에 `.env.example`의 값을 모두 채운 뒤 실행합니다. `ACCOUNT_KEY`, `KAKAO_REST_KEY`, `MAIL_HOST`가 없으면 서버가 시작을 거부합니다. DB 구조는 Flyway가 만들고, 시작할 때 엔티티와 일치하는지 검사합니다.

```bash
docker compose up -d --build
```

`/actuator/health`로 상태를 확인합니다. 로그를 파일로 남기려면 `SPRING_PROFILES_ACTIVE=logfile`과 `LOG_DIR`을 넣습니다.

## 개발

```bash
./mvnw verify                 # 서버와 엔진 테스트
cd frontend
npm run dev                   # 5173 포트, API는 8080으로 전달
npm run lint
npm test
npm run e2e                   # 서버 jar와 화면 빌드 뒤, PC와 휴대폰 화면으로 흐름 확인
```

`MYSQL_TEST_URL`을 넣으면 `MigrationSchemaTest`가 실제 MySQL에서 돕니다. CI는 매번 MySQL 8.4에서 이 검사를 합니다.

## 보안

- API는 기본 거부. 공개 경로는 `AccessInterceptor`에 있는 목록뿐이고, 테스트가 모든 경로를 훑어 확인합니다
- 로그인 코드와 유지 토큰은 해시로, 계좌번호는 AES-GCM으로 저장합니다
- 코드 실패는 코드별 5회, 하루 20회로 막고, 인증 경로는 IP별 분당 30회로 제한합니다
- 다른 사이트의 쓰기 요청은 Origin 검사로 막고, 결제 사진은 파일 앞부분으로 형식을 확인합니다

## 아직 확인하지 못한 것

- 심야, 시외 할증에서의 카카오 요금 값
- 길찾기 값 저장 허용 여부
- 초기 거점과 지점 좌표는 시연용 샘플입니다

## 알림

확정, 미성사, 일행 변경과 취소, 결제자 변경, 승차 10분 전 안내는 메일과 웹 푸시로 갑니다. 푸시는 내 정보에서 알림 받기를 누른 기기에만 가고, `PUSH_PUBLIC_KEY`와 `PUSH_PRIVATE_KEY`가 없으면 꺼집니다. 시연 프로필은 실행마다 새 키를 만듭니다.

## 아직 없는 것

신고, 관리 화면. 요청 제한과 실시간 신호는 서버 한 대 기준입니다.

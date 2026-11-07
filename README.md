# 캠퍼스 택시메이트

같은 거점에서 출발해 다른 곳에서 내리는 학생을 자동으로 매칭하고, 택시비를 단독 요금 비율로 나누는 모바일 웹이다. 앱은 택시 호출, 실제 결제, 송금을 하지 않는다.

## 구성

- `matching-engine/`: 매칭 추정 단계와 정산 계산. DB, 스프링, 카카오에 의존하지 않는 순수 Java 모듈이다.
- `server/`: Spring Boot API. 인증, 요청, 슬롯 스케줄러, 일행, 정산, 시연 모드.
- `frontend/`: React 화면. 빌드하면 `server`의 정적 파일로 들어간다.
- `docs/sample-confirmed-party.json`: 확정 일행 응답 샘플.

## 키 설정

저장소 최상위에 `.env`를 만든다. Git에는 올리지 않는다.

```
KAKAO_REST_KEY=REST API 키
VITE_KAKAO_JS_KEY=JavaScript 키
```

JavaScript 키에는 카카오 콘솔에서 사이트 도메인(`http://localhost:8080`)을 등록해야 지도가 뜬다. 화면은 빌드할 때 키를 넣으므로 키를 바꾸면 다시 빌드한다.

## 시연 실행

MySQL 없이 돌아가고 데이터는 재시작하면 사라진다. `KAKAO_REST_KEY`가 없으면 경로와 요금은 모의 값이다.

```bash
cd frontend
npm install
npm run build
cd ..
mvn package -DskipTests
set -a; . ./.env; set +a
SPRING_PROFILES_ACTIVE=demo java -jar server/target/server-0.1.0.jar
```

`http://localhost:8080`에서 `demo-f1@daejin.ac.kr`을 넣는다. 시연 계정은 코드가 자동으로 들어간다. 화면 아래 시연 모드로 진행한다.

1. 탑승 요청을 넣고 샘플 요청 2개를 누른다.
2. +5분을 누르면 일행이 확정된다.
3. 샘플 일행 도착, 보냄과 도착했어요를 누른다.
4. +30분을 누르면 출발 처리된다.
5. 마지막 하차자가 금액과 사진을 올린다.
6. 샘플 일행 도착, 보냄을 누르고 받았어요를 누른다.

## 실제 실행

```bash
docker compose up -d mysql
KAKAO_REST_KEY=키 ACCOUNT_KEY=계좌암호화키 MAIL_HOST=메일서버 MAIL_USER=계정 MAIL_PASSWORD=비밀번호 java -jar server/target/server-0.1.0.jar
```

키가 없으면 경로 조회가 실패해 요청을 저장하지 못한다. 메일 서버가 없으면 로그인 코드는 서버 로그에 찍힌다. 실제 사용자는 메일 서버 없이 로그인할 수 없다.

## 테스트

```bash
mvn install -DskipTests -pl matching-engine
mvn test
```

## 아직 확인하지 못한 것

- 심야, 시외 할증에서의 카카오 요금 값. 낮 시간대 호출만 확인했다.
- 길찾기 값 저장 허용 여부.
- MySQL 실행. 테스트는 H2의 MySQL 모드로만 돌렸다.
- 거점과 하차 지점 좌표. 초기 데이터는 시연용 샘플이다.

## 아직 없는 것

장소 검색, 신고, 관리 화면.

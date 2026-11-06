# 캠퍼스 택시메이트

같은 거점에서 출발해 다른 곳에서 내리는 학생을 자동으로 묶고, 택시비를 구간 비율로 나누는 서비스다.

## 지금 있는 것

- `matching-engine/`: 묶기 추정 단계. DB, 스프링, 카카오에 의존하지 않는 순수 Java 모듈이다.
- `docs/sample-confirmed-party.json`: 확정 일행 샘플. 정산과 화면 개발은 이 형식으로 먼저 시작한다. 금액은 구현 기준의 15,000원 예시다.

## 실행

```bash
cd matching-engine
mvn test
```

Java 21과 Maven이 필요하다.

## 엔진 사용

`MatchingRound.run(slot, requests, verifier)`를 5분 슬롯마다 부른다. 대기 요청과 `GroupVerifier`를 넘기면 확정 일행과 미성사 요청을 돌려준다.

`GroupVerifier`는 뽑힌 조합을 실제 경로 값으로 확인하는 자리다. 카카오 구현은 요금 값과 저장 정책을 확인한 뒤 만든다.

엔진은 시계를 모르고 저장도 하지 않는다. 다음 슬롯까지 끝나지 않은 결과는 스케줄러가 저장하지 않고 버린다.

## 아직 없는 것

카카오 확인 단계, Spring Boot 서버, DB, 정산, 화면.

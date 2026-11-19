# 2026-11-20 검증 기록 (디자인 정리 + C7 상세 모의 운영)

로컬 main에서 실제로 실행한 검증만 적는다. 대상: 화면 디자인 정리(`acb9a07`, `99e6a69`, `483211e`), C7 상세 모의 운영과 2차 경로 검증 설정값 사용(`906b41c`).

| 대상 | 결과 |
|---|---|
| 매칭 엔진·서버 (`mvn -B verify`, MySQL 8.4.3 컨테이너, CI와 같은 환경 변수) | 엔진 73개, 서버 480개 통과. 실패·오류·건너뜀 0 |
| 프런트 (`npm run lint`, `npx vitest run`, `npm run build`) | lint 통과, 112개 통과, 빌드 성공 |
| e2e (`CI=1 TAXIMATE_E2E_CHROME=1 npx playwright test`) | 6개 통과 (PC·휴대폰 화면 크기) |
| 실험 도구 (`experiments/.cache/venv/python.exe -m unittest discover -s experiments/tests`) | 36개 통과. C7 실제 Java 연동 검사는 `detailed.py --only base`로 컴파일 후 실행 |
| C7 재현 | 이 체크아웃에서 기본 조건 다시 실행 시 매칭률 57.60% [55.93, 59.30]로 보고서와 같음 |

## 바뀐 것

- 화면: 색·글자·둥글기 토큰 통일, 안내 상자·설명문 축소, 로그인 첫 화면, 탑승 번호 크게, 모집 카드(인원 점), 요금 비교 막대, 이용 내역 정렬.
- 서버: `RouteGroupVerifier` 2차 검사가 15분·10%를 고정값으로 쓰던 것을 `MatchingSettings` 값으로 바꿨다. 기본값은 같아 앱 동작은 그대로다(경계 테스트 M-01·M-02 통과).
- 실험: C7 20조건×30일(91,500요청). 노쇼는 실제 `CompensationService.apply()` 적용 후를 주 결과로, 신뢰구간은 일 단위 부트스트랩 10,000회.

## 확인하지 않은 것

실제 휴대전화 기기, 다른 PC 설치. GitHub CI는 푸시 후 확인한다.

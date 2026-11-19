# 2026-11-19 최종본 검증 기록

11/23 교수님 미팅용 최종본(로컬 main, 공개자료 실험 병합 `ade0c02` + 탑승 미확인 제외자 수정 병합 `01aefe3`)에서 실제로 실행한 검증만 적는다.

## 실행 환경

- Java 21 (Microsoft OpenJDK 21.0.12), Maven 3.9.11, Node 20, Docker 28.5.1
- MySQL은 CI(`.github/workflows`)와 같은 `mysql:8.4.3` 컨테이너를 `127.0.0.1:3316`에 띄우고 CI와 같은 환경 변수(`MYSQL_TESTS=true`, `DB_URL`, `MYSQL_TEST_URL`, `MYSQL_MIGRATION_TEST_URL`)로 실행했다. `taximate`, `taximate_migration` DB는 실행 전에 새로 만들었다.

## 결과

| 대상 | 명령 | 결과 |
|---|---|---|
| 매칭 엔진 | `mvn -B verify` | 73개 통과, 실패·오류·건너뜀 0 |
| 서버 (실제 MySQL 포함) | `mvn -B verify` | 480개 통과, 실패·오류·건너뜀 0. Flyway V1~V21 신규 설치와 V20→V21 이관 포함 |
| 프런트 | `npm ci`, `npm run lint`, `npx vitest run`, `npm run build` | lint 통과, 15파일 112개 통과, 빌드 성공 |
| 실험 도구 | `experiments/.cache/venv/python.exe -m unittest discover -s experiments/tests` | 23개 통과 |
| 서버 패키징 | `mvn -B -DskipTests -pl server -am package` | 성공. 첫 `verify`의 repackage 단계는 이전 세션의 시연 서버(8088)가 jar를 잠가 실패했고, 그 프로세스를 종료한 뒤 다시 패키징했다 |

## 이번에 고친 문제

- 탑승 번호를 입력하지 못해 일행에서 제외된 사람(결제자 승계 제외 포함)이 이용 내역에서 `취소`로만 보이고, 본인 선결제 기록 조회가 일행 권한(403)에 막혔다. 종료 사유를 저장하고(V21), 이용 내역에 탑승 조사 상태와 소명 화면 이동을 표시하며, 본인 결제 주문만 읽는 `/api/payments/history/{requestId}`를 추가했다. 일행 상세 권한은 넓히지 않았다. 11/19 시연 모드(demo-f1, 선결제 후 번호 미입력)에서 재현했고, 수정 후 HTTP로 `BOARDING_UNCONFIRMED` 표시·본인 기록 조회·타인 기록 403을 확인했다.
- 고정 마감 시각을 쓰던 탑승번호 프런트 테스트 3개가 날짜가 지나면서 실패했다. 컴포넌트에 서버 시각(`now`)을 넘기도록 고쳤다.

## 실험 (공개 실제 운행 기록)

- C5 한국 주 실험: 서울시설공단 장애인콜택시 탑승내역 2025(1,729,476행, SHA256 `f704ecea…5889`). 학습 1~9월, 평가 10~12월 277,978건. 일반택시 요금 환산 MAPE 6.20%, ±15% 이내 88.39%, +15% 초과 9.45%. 장콜 요금표 공식 검증 MAPE 2.03%. 상세: `experiments/reports/public-fares.md`, 입력 목록: `public-data-MANIFEST.json`.
- C6: 한국 캐시 768건 매칭(661명) 결과에 C5 경험 오차를 정산 단계에만 1,000회 적용. 비율 정산 평균 실제 절약 47.86%, 손해 1.42%(1/N 2.81%), 비결제자 추가 결제 9.52%, 합계 오류 0. 상세: `experiments/reports/c6-error-sensitivity.md`.
- 한계: 일반택시 영수증 검증이 아니며, 요청은 TIMS 승차 통계 기반 대리 수요다.

## 확인하지 않은 것

- 실제 휴대전화 기기, 다른 PC에서의 설치·실행 재현 (12/06 전 예정)
- GitHub CI 실행: 이 기록 시점에는 원격에 올리지 않았다.

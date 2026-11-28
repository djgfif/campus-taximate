# 2026-11-28 검증 기록 (정산 흐름 v3 전환 뒤 전체 재검증)

`docs/final-deck-evidence` 브랜치 `d86ac18`에서 실제로 실행한 검증만 적는다. 대상: 11/20 이후 바뀐 후불 v3 기본 전환(`23a7323`), UI 리뉴얼(`93a432f`, `961700a`, `0bd0dfa`), 장소 검색 서버 경유(`4e0cbc0`), 외부 통학 거점(`3c4ff3c`), C12 재실행(`7be2e25`, `45386a2`), 문서 정리(`a6ca907`, `d86ac18`).

| 대상 | 결과 |
|---|---|
| 매칭 엔진·서버 (`mvnw -B verify`, 별도 MySQL 8.4.3 컨테이너, CI와 같은 환경 변수, `MYSQL_TESTS=true`) | 엔진 77개, 서버 595개 통과. 실패·오류·건너뜀 0. MySQL 전용 4종(`BoardingMigrationMysqlTest`, `PaymentFlowMysqlTest`, `AuthConcurrencyMysqlTest`, `ConfirmationConcurrencyMysqlTest`) 포함 |
| 프런트 (`npm run lint`, `npx vitest run`, `npm run build`) | lint 통과, 24개 파일 207개 통과, 빌드 성공 |
| e2e (`CI=1 TAXIMATE_E2E_PORT=8090 TAXIMATE_E2E_CHROME=1 npx playwright test`, 새 화면 빌드를 넣은 jar) | 8개 통과, 2개 건너뜀. 건너뜀은 SPA 경로·10개 해상도 검사를 PC 프로젝트에서만 돌리도록 한 설계(`ui-quality.spec.js`)이며 PC에서 통과 |
| 실험 도구 (`experiments/.cache/venv/python.exe -m unittest discover -s experiments/tests`) | 118개 실행, 통과(건너뜀 2) |

11/20 기록(엔진 73, 서버 480, 프런트 112, e2e 6, 실험 36)보다 늘어난 수는 그 뒤 추가한 v3·UI·실험 테스트다.

## 실행 환경

- Windows 11, Docker 28.5.1, MySQL 8.4.3(검증 전용 컨테이너, 3316 포트, 검증 후 삭제). 시연용 `taximate-demo-mysql`과 실행 중인 시연 서버(8080)는 건드리지 않았다.
- 로컬 JDK는 24.0.2다. CI는 JDK 21이므로 21 기준 결과는 GitHub CI로 확인한다.

## 확인하지 않은 것

실제 휴대전화 기기, 다른 브라우저(`e2e:compat`), 팀원 PC 설치 재현, 실제 카카오 키를 넣은 경로·요금(이번 실행은 모의 경로), 이 커밋의 GitHub CI.

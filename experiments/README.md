# 캡스톤 실험 재현

현재 앱 소스를 사용하는 오프라인 실험 도구다. Python 3.10 이상과 JDK 21, Maven이 필요하다. Python 추가 패키지나 API 키는 필요하지 않다.

## 한 번에 실행

저장소 루트에서 실행한다. `JAVA_HOME`은 JDK 21을 가리켜야 한다. 실행기는 Windows의 `~/.jdks/`에서도 JDK 21을 찾으며 `-JavaHome`으로 지정할 수 있다.

```powershell
./experiments/run.ps1 -Build
python -m unittest discover -s experiments/tests -v
```

`-Build`는 현재 Maven 앱을 패키징한다. 이미 패키징했다면 생략할 수 있다. 그래도 실험은 현재 `matching-engine` 전체 소스와 서버의 `RouteGroupVerifier`, `RouteClient`, `RoutePathStore`, `MatchingDiagnostics`를 다시 컴파일한다. 과거의 컴파일된 매칭 알고리즘을 재사용하지 않는다. 필요한 런타임 의존성은 Maven이 만든 서버 패키지의 `BOOT-INF/lib`에서 읽고, 컴파일 전용 Lombok은 로컬 Maven 저장소에서 읽는다. 의존성 해시와 직접 컴파일한 소스 해시를 결과에 남긴다. 소스가 컴파일 도중 바뀌면 중단한다.

`-Maven`에는 별도 Maven 실행 파일을 지정할 수 있다. 입력·클래스·의존성 캐시는 모두 이 폴더의 `.cache/` 아래에 둔다. 앱의 `target/`를 삭제하거나 기존 DB를 건드리지 않는다.

## C5: 실제 예상액과 결제액

1. `templates/actual_fares.csv`를 `private/actual_fares.csv`로 복사한다.
2. 실제 탑승 기록을 입력한다. 목표는 **탑승 전 예상액과 결제액이 짝지어진 30건**이다. 예시 데이터나 가상 결제는 입력하지 않는다.
3. 다음 명령을 실행한다.

```powershell
python experiments/fares.py experiments/private/actual_fares.csv
```

| 필드 | 입력 기준 |
|---|---|
| `record_id` | 중복되지 않는 익명 식별자. 이름·이메일·카드번호 금지 |
| `cohort` | `prospective`: 탑승 전에 저장한 예상액 / `historical_requery`: 과거 결제액과 현재 재조회 |
| `origin_label`, `destination_label` | 비공개 대조용 구간 이름. 공개 결과에는 포함하지 않음 |
| `ride_at`, `quote_at` | 시간대가 있는 ISO 8601. 예: `2026-11-19T12:00:00+09:00` |
| `expected_krw`, `actual_krw` | 양의 정수 원화. 실제액은 결제 증빙을 확인 |
| `currency` | `KRW` |
| `expected_toll_scope`, `actual_toll_scope` | 통행료 포함 `included` / 제외 `excluded`. 두 값 일치 필요 |
| `expected_surcharge_scope`, `actual_surcharge_scope` | 할증 포함 여부. 두 값 일치 필요 |
| `quote_source` | `kakao_quote` 또는 `recorded_app_quote` |
| `actual_source` | `meter_receipt` 또는 `payment_receipt` |

영수증 파일과 대조 근거는 `private/`에 보관한다. CLI는 진위 자체를 검증할 수 없으므로 팀원이 실제 증빙을 확인해야 한다. 이름·계좌·정확한 집 주소는 저장하지 않는다. 중복 ID 또는 같은 구간·시각·결제액의 기록은 전부 제외한다. 모호한 통행료/할증 범위, 시간대 누락, 비정상 금액은 제외 사유와 건수를 남긴다.

`reports/actual-fares.json`과 `.md`에는 집계만, `private/actual-fares-details.json`에는 개별 오차·제외 사유를 저장한다. 공개 보고서에 개별 구간·기록 ID를 넣지 않는다.

- 부호 있는 상대 오차: `(실제액 - 예상액) / 예상액`.
- MAE: 평균 절대 금액 차이. MAPE: `평균(abs(실제액 - 예상액) / 실제액)`.
- ±15% 이내와 +15% 초과 판정의 분모는 예상액이다. 정확히 15%는 이내에 포함한다.
- 평균·중앙값·p90·p95·최솟값·최댓값을 기록한다. 분위수는 선형 보간 방식이다.
- 두 수집 집단은 합산하지 않는다. 과거 영수증과 현재 경로 조회는 당시 예측 정확도가 아니다.
- 입력이 없으면 `n=0`, 지표는 `null/미측정`이다. 0% 오차나 안전성 입증으로 해석하지 않는다.

## C6: 현재 매칭과 두 정산 방식

기본 실행은 고정 시드로 생성한 **가상 요청**을 사용한다. 시드와 시행 수를 바꿀 수 있다.

```powershell
./experiments/run.ps1 -Seed 20261119 -Trials 12
```

매칭은 실제 `MatchingRound`, 2차 검증은 서버의 실제 `RouteGroupVerifier`, 정산은 실제 `SettlementCalculator`를 호출한다. 동일하게 매칭된 일행·결제액에 대해 단독 요금 비율 정산과 동일 가중치 1/N 정산을 비교한다. 1원 단위 반올림 규칙도 같은 계산기를 사용한다. 다른 매칭 알고리즘과의 우열 비교를 의미하지 않는다.

요청 수 = 매칭 수 + 만료 수를 검사하고, 각 요청이 중복 사용되지 않는지와 두 정산의 금액 합이 결제액인지 확인한다. 결과에는 전체 요청 매칭률, 매칭자/전체 요청의 평균 절감률, 매칭자의 지연, 절감률·지연의 분포, 총 절감액, 1/N에서 10% 절감을 못 얻는 인원을 구분한다. 미매칭자의 절감은 0으로 계산한다. 2차 검증 통과·실패·조회 불가 횟수는 사람 수와 별도로 센다. 실행한 엔진 설정은 런타임 객체에서 읽어 보고서에 기록한다. 현재 실험 입력은 공통 거점 출발 기준 예약 요청이며 걷기 0초·하나의 성별 풀을 가정한다. 즉시·도착 기준·걷기·다중 승차 지점의 전체 효과를 검증한 결과가 아니다. 벽시계 제한이 있는 엔진이므로 입력은 재현되어도 경계 상황의 결과·실행 시간은 기계 부하에 따라 달라질 수 있다.

### 기존 한국 캐시 재사용

```powershell
./experiments/run.ps1 -LegacyRealdata '../taximate-matching-sim/realdata' -Limit 60 -OutputPrefix korea-cached
./experiments/run.ps1 -LegacyRealdata '../taximate-matching-sim/realdata' -LegacyExperiment timeline -Limit 12 -OutputPrefix korea-timeline
```

기존 `data/korea_cases.txt`에서 공통 승차 지점 단일 회차 사례를 모은 뒤 원본 순서에서 균등 간격으로 선택하고 `kakao_matrix.tsv`를 읽는다. 따라서 앞부분의 4명 사례만 고르지 않고 여러 요청 규모를 포함한다. 선택 방법과 규모별 사례 수를 결과에 기록한다. 원본은 수정하지 않고 필요한 입력을 `.cache/legacy/`로 복사한다. 과거 실험의 다른 DP 알고리즘이나 복제 엔진은 가져오지 않는다. 원본·선택 자료 해시와 과거 1,490회 호출 이력을 기록하고, 이번 실행의 신규 호출은 별도 0회로 기록한다.

이 자료는 TIMS 수요 집계에서 만든 대리 입력이며 실제 학생 OD가 아니다. 일부 시간은 가상이고, 행렬의 약 51.5%는 관측 구간을 합성했다. 다중 경유 요금은 단독 카카오 견적에 맞춘 모델이다. **과거 보고서의 MAPE 14.35%는 모델과 카카오 견적의 차이이며 실제 미터 결제액 오차가 아니다.** 여기서 높은 매칭률이 나와도 실제 서비스 효과로 일반화하지 않는다.

`timeline`은 기존 시계열 입력의 요청 도착·마감을 유지한 채 1분 엔진 회차와 5분 예약 규칙으로 반복한다. 매칭된 요청과 만료 요청은 풀에서 제거한다. 단일 회차 입력과 시계열 입력의 결과는 각각 보고하고 합산하지 않는다.

보고서의 지연은 `max(0, 걷는 시간 + 동승 시간 - 단독 시간)`이다. 단독보다 빨라진 시간을 음의 지연으로 평균에 상쇄하지 않는다.

### 실측 오차 민감도

```powershell
./experiments/run.ps1 -ActualFares experiments/private/actual_fares.csv
```

검증을 통과한 `prospective` 표본이 있으면 부호 있는 오차 p90/p95를 공유 경로 요금 배수로 적용하여 같은 요청을 다시 매칭·검증·정산한다. 단독 견적 가중치는 원래 값을 유지한다. 전체 경로에 같은 배수를 적용하는 스트레스 분석이며 현실의 오차 분포나 신뢰구간 추정이 아니다. 표본 수를 결과에 남기고, 실측 표본이 없으면 `BLOCKED_NO_PROSPECTIVE_FARES`로 표시한다.

## 결과·공개 범위

- `reports/*.csv`: 사례별 집계. 개인 정보·좌표·요청별 결과 없음.
- `reports/*.json`: 소스/의존성/자료 해시, 코드 버전과 dirty 여부, 설정, 집계, 민감도 상태.
- `reports/*.md`: 발표용 설명과 자료 한계.
- `baseline/`: 기존 실험을 재사용한 근거와 재현 방식.
- `.cache/`, `private/`: Git 제외. 원본·영수증·API 키를 제출 묶음에 포함하지 않는다.

이 도구에는 네트워크 호출 경로가 없다. 기본 및 캐시 실험의 신규 API 호출은 **0회**다. 승인된 신규 호출 예산 100회를 자동 소모하거나 과거 호출량을 초기화하지 않는다. 향후 온라인 수집 도구를 추가할 경우 실패 요청도 포함하는 지속적 호출 장부와 100회 상한을 먼저 구현해야 한다.

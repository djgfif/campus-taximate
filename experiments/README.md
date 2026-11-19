# 캡스톤 실험 재현

현재 앱 소스를 사용하는 모의실험 도구다. Python 3.10 이상과 JDK 21, Maven이 필요하다. 기본/개인 CSV 모드는 표준 라이브러리만 쓰며, **C5 공개자료·C7 수요 추정에는 DuckDB가 필요**하다. API 키는 사용하지 않는다. **팀원 실제 탑승 30건 수집 계획은 폐기했다. 한국 자료가 발표 주 결과이고 NYC는 참고다.**

## C5 공개자료 주 실험

표준 Windows Python에서는 저장소 안에 가상환경을 만든다. 수집기는 Node.js 18 이상을 사용한다.

```powershell
python -m venv experiments/.cache/venv
./experiments/.cache/venv/Scripts/python.exe -m pip install -r experiments/requirements-public.txt
node experiments/acquire-public.mjs all
./experiments/.cache/venv/Scripts/python.exe experiments/fares.py --public
```

이번 PC의 기본 `python`은 MSYS Python 3.12.11이므로 Windows DuckDB wheel을 읽지 못한다. 실제 실행은 공식 CPython 3.12.10 임베디드 런타임을 `.cache/venv/`에 풀고 공식 PyPI의 `duckdb==1.4.3` Windows wheel을 그 런타임의 `Lib/site-packages/`에 설치했다. 이 격리 런타임의 명령은 다음과 같다. 시스템 Python이나 전역 패키지는 바꾸지 않았다.

```powershell
./experiments/.cache/venv/python.exe experiments/fares.py --public
python -m unittest discover -s experiments/tests -v
./experiments/.cache/venv/python.exe -m unittest discover -s experiments/tests -v
```

수집·분석은 `fares.py --public --download`로도 연결된다. 수집 실패가 있으면 독립적으로 확보된 자료의 분석은 계속한다. 원본은 모두 `.cache/`, 재현 가능한 출처·크기·SHA256·행 수는 `reports/public-data-MANIFEST.json`과 `public-fares.json`에 남긴다. 이미 있는 파일은 다시 받지 않으며, 원본 변경을 확인하려면 별도 캐시로 새로 수집한다. 포털 수집은 같은 쿠키로 상세 페이지 GET → 다운로드 메타데이터 **POST**(`X-Requested-With`) → 제한 확인 POST → 첨부 GET 순서다. CAPTCHA가 필요하면 해당 자료를 중단하며 우회하지 않는다. 로그인·약관 승인·데스크톱 조작은 하지 않는다.

- **한국 주 자료**: 서울시설공단 2025 장애인콜택시 개별 운행, 1~9월 학습 / 10~12월 평가. 입력 열은 승하차·취소 시각, 출발/목적 구·동, 요금, 승차거리만 허용한다. 민감 속성은 분석 테이블과 결과에 넣지 않는다.
- **사전 고정 규칙**: 같은 OD·평일/주말×6시간대 최소 20건. 부족하면 OD 전체 시간 20건으로 후퇴하고 그래도 부족하면 평가 제외한다. 평가기간 요금·거리·시간으로 예측값을 학습하지 않는다.
- **공식 검증을 먼저**: 실제 거리→장콜 공식(5km 1,500원, 5~10km 280원/km, 이후 70원/km, 100원 미만 절사)과 기록 요금을 비교한다. 이후 학습 OD 거리→장콜 요금, 학습 OD 실제 요금 중앙값→기록 요금을 비교한다.
- **발표 주 지표**: 학습 경로와 실제 경로 각각에 서울 일반 중형택시 2023 요금표를 적용한다. 이 지표의 실제액은 **일반택시 환산액**이며 일반택시 영수증이 아니다. 저속 시간은 `max(0, 총초−거리/15km/h)`로 근사하고, 기본거리 이후 시간 tick을 더한다. 승차 시각 심야율을 전체 여정에 고정하고 시계외·통행료를 제외한다.
- **TIMS**: 공식 2025 월별 원본을 격자-월 단위로 분석한다. 대진대 5~25km 통학권 격자 선택은 기존 연간 캐시를 읽기만 한다. 서울/경기 표준형과 포천(2km·83m·20초) 요금표를 따로 비교한다. 집계 평균에 비선형 요금표를 적용하는 편향과 지역 요금제 혼합 때문에 정확한 미터 공식 입증은 아니다.
- **NYC 참고**: 공식 페이지의 최신 Yellow 월 T와 직전 T−1을 자동 선택한다(이번 T=2026-09, T−1=2026-08). RatecodeID=1, 양의 fare_amount, 거리 0.1~160km, 1~180분, 평균속도 1~130km/h, 올바른 시각 순서·해당 월·구역만 사용한다. 사유는 첫 일치 기준으로 배타 집계한다. 공통 금액 상한은 1,000,000이며 실제 통화는 JSON에 명시한다.
- NYC 공식은 2022-12-19 이후 기본 $3 + 1/5마일당 $0.70(12mph 초과) 또는 저속/정차 분당 $0.70이다. `fare_amount`만 비교하며 MTA·improvement·혼잡·CBD·공항·팁·통행료는 별도 열이므로 더하지 않는다. 저속 clock이 없어 `max(거리 tick, 전체 분 tick)` 하한 근사를 사전 고정하고 거리+전체시간 상한 근사도 함께 공개한다. **c 재구성 오차를 먼저 보고 a 경로 예측을 해석**하며, 좋은 수치에 맞춰 공식을 바꾸지 않는다.
- 부호 오차 `(실제−예상)/예상`, MAPE 분모는 실제, 임계값과 중앙 절대오차율 분모는 예상이다. 5/10/25/50/75/90/95 백분위·거리대·시간대와 요금 재구성/거리/시간 요인 분해를 JSON에 보존한다. 시간 요인은 교통 변동을 포함하지만 인과적 교통 효과만 분리한 값은 아니다.

결과는 `reports/public-fares.json/.md`이다. 과도한 오차나 데이터 한계도 결과로 남기며 실제 운영 가능성으로 일반화하지 않는다. 출처 URL은 보고서와 JSON의 `sources`에서 확인한다.

## 한 번에 실행

저장소 루트에서 실행한다. `JAVA_HOME`은 JDK 21을 가리켜야 한다. 실행기는 Windows의 `~/.jdks/`에서도 JDK 21을 찾으며 `-JavaHome`으로 지정할 수 있다.

```powershell
./experiments/run.ps1 -Build
python -m unittest discover -s experiments/tests -v
```

`-Build`는 현재 Maven 앱을 패키징한다. 이미 패키징했다면 생략할 수 있다. 그래도 실험은 현재 `matching-engine` 전체 소스와 서버의 `RouteGroupVerifier`, `RouteClient`, `RoutePathStore`, `MatchingDiagnostics`를 다시 컴파일한다. 과거의 컴파일된 매칭 알고리즘을 재사용하지 않는다. 필요한 런타임 의존성은 Maven이 만든 서버 패키지의 `BOOT-INF/lib`에서 읽고, 컴파일 전용 Lombok은 로컬 Maven 저장소에서 읽는다. 의존성 해시와 직접 컴파일한 소스 해시를 결과에 남긴다. 소스가 컴파일 도중 바뀌면 중단한다.

`-Maven`에는 별도 Maven 실행 파일을 지정할 수 있다. 입력·클래스·의존성 캐시는 모두 이 폴더의 `.cache/` 아래에 둔다. 앱의 `target/`를 삭제하거나 기존 DB를 건드리지 않는다.

## C5: 선택적 개인 CSV (기존 기능 유지)

1. `templates/actual_fares.csv`를 `private/actual_fares.csv`로 복사한다.
2. 별도로 이미 보유한 진짜 짝 자료가 있을 때만 입력한다. 팀원 탑승 수집은 하지 않는다. 예시 데이터나 가상 결제를 실측으로 입력하지 않는다.
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

### 공개 운행 오차 부트스트랩

```powershell
./experiments/run.ps1 -JavaHome 'C:/Users/gkho0/.jdks/ms-21.0.12.1' -LegacyRealdata '../taximate-matching-sim/realdata' -ActualFares experiments/reports/public-fares.json -Bootstrap 1000 -OutputPrefix korea-cached
./experiments/run.ps1 -JavaHome 'C:/Users/gkho0/.jdks/ms-21.0.12.1' -ActualFares experiments/reports/public-fares.json -Bootstrap 1000 -OutputPrefix synthetic
```

기본 `-ActualFares`는 C5 공개 JSON이다. 매칭은 예상 요금으로 한 번만 끝내고, 확정 일행을 고정한 채 한국 주 지표의 경험적 ε를 일행과 각 단독 실제 요금에 독립 추출한다. 실제액은 예상액×(1+ε), 비율 가중치는 원래 단독 예상액이다. 같은 실제액·결제자로 1/N과 비교한다. 선결제는 실제 `PaymentService.prepayAmount`의 15% 올림을 적용하며 결제자는 제외한다.

시드 20261119, 1,000회 전체 일행 재생을 기본으로 ε=0, ε, 1.5ε, 2ε를 나란히 보고한다. 같은 난수를 조건·정산 사이에 공유한다. 확대 배수가 비양수 요금을 만드는 일행-반복은 해당 조건의 분모에서 제외하고 건수를 공개한다. 확대 결과는 양수 요금에 조건부이며 무조건 평균으로 해석하지 않는다. 환불액 평균은 환불 발생 비결제자 기준이다. 절약·손해 비율은 확정 매칭자 기준이다. 기존 개인 CSV를 지정해도 같은 정산 단계 부트스트랩을 선택적으로 쓸 수 있다.

`reports/c6-error-sensitivity.json/.md`에 한국 캐시와 가상 요청을 분리하며 한국 오차가 주 결과, NYC 오차가 참고다. 반복 정산 건수를 관측 운행 건수로 세지 않는다. 생산 코드 재컴파일은 Lombok 처리 때문에 서버·엔진 전체 소스를 명시적으로 포함한다. JDK 21 확인 후 `-source 21 -target 21`로 외부 JDK `ct.sym` 접근 문제를 피한다.

## C7: 공개자료로 만든 상세 모의 운영

```powershell
# 선택적 추가 공개자료. 경기 정류소를 못 받으면 실패 사유를 저장하고 exit 1;
# 확보된 포천 승차장과 기존 서울/TIMS 자료로 C7은 계속 실행할 수 있다.
node experiments/acquire-public.mjs c7

# 패키지가 현재 worktree에 있으면 지정 없이 실행한다.
./experiments/.cache/venv/python.exe experiments/detailed.py --days 30 --verify-c6

# 이번 worktree는 target이 없으므로 옆 checkout의 jar에서 의존성만 읽었다.
# 앱 클래스는 그 jar에서 실행하지 않고 이 worktree의 전체 소스를 재컴파일한다.
./experiments/.cache/venv/python.exe experiments/detailed.py --days 30 --verify-c6 --boot-package ../campus-taximate/server/target/server-0.1.0.jar
./experiments/.cache/venv/python.exe -m unittest discover -s experiments/tests -v

# 이미 완료된 C7 원시 출력으로 표만 다시 작성. 입력·Java 소스·원시 출력 해시를 검증한다.
./experiments/.cache/venv/python.exe experiments/detailed.py --reuse-output --boot-package ../campus-taximate/server/target/server-0.1.0.jar
```

JDK 기본값은 `.cache/jdk21`, 다른 위치는 `--java-home C:/Users/gkho0/.jdks/ms-21.0.12.1`이다. 새 빌드는 `--build --maven C:/dev/apache-maven-3.9.11/bin/mvn.cmd`로 가능하다. `--public-cache`로 C5 원본·정규화 파일 폴더, `--legacy-realdata`로 읽기 전용 기존 한국 캐시를 지정한다. `--only base`는 30일 진단만 수행하고 공개 보고서를 덮어쓰지 않는다. 진단 캐시가 전체 캐시를 대체하므로 이후 전체 실행을 다시 해야 한다.

- **설계**: 기본 하루 100건·지연 15분·절약 10%·즉시 30%·노쇼/미납 0%·C5 한국 경험 오차. 요청 50/100/200/400×지연 10/15/20의 12조건에 절약 5/15%, 즉시 0/60%, 노쇼 5%, 오차 없음, 미납 5%, 노쇼 5%+오차 없음 8조건을 더해 총 20조건이다. 조건마다 독립 시드 30일 이상이며 같은 일자의 조건 간 난수는 공유한다. 오차·노쇼만 달라지는 경우 동일 확정 일행을 재사용한다.
- **수요**: 실제 TIMS 2025 월별 격자 승차 합계를 기존 캐시에서 단독 견적까지 있는 118개 대표점에 연결한다. 같은 격자의 여러 대표점에는 격자 가중치를 분할한다. 후보 120개 중 단독 캐시 없는 2개는 제외하고 ID를 기록한다. 대진대 출발·걷기 0·동일 성별 풀이다. 학생 OD 실측이 아니다.
- **시각**: 서울 장콜 2025 평일·정상 승하차 순서·취소 없음의 시간 분포를 사용한다. 12~21시에 제한하고 12/13시×0.5, 14/15시×1, 16~18시×2, 19/20시×1.5, 21시×1로 재가중한다. 시간 안은 균등 도착, 예약 출발창은 요청+25~45분(모집 20분)이라는 가정이다. 실제 학생 하교 분포로 표현하지 않는다.
- **운영·정산**: 생산 `MatchingRound`의 1분 회차/5분 예약 규칙, `RouteGroupVerifier`, `SettlementCalculator`, `PaymentService.prepayAmount`를 호출한다. `RouteGroupVerifier`의 고정 15분/10% 검사를 전달된 설정으로 교체했으며 기본값은 유지했다. 미납은 투입 전 참여 감소 대리 조건이다. 노쇼는 확정 후 이탈·기존 청구액 유지 조건에서 실제 **`CompensationService.apply()` 적용 후 순부담을 주 결과**로 보고하며 보전 전 수치는 `raw_` 참고 지표다. 실제 결제 상태 머신 전체를 재현한 결과는 아니다.
- **노쇼 보전**: `experiments/java/com/campustaximate/server/service/SimulationCompensation.java`가 원래 예상 분담금 스냅샷·노쇼 판정·실제 몫을 만들고, 저장소만 실험 내부 메모리로 연결해 서버의 `CompensationService.apply`를 직접 호출한다. 실제 `SettlementCalculator.compensation`이 생존자별 `max(0, 실제 몫−확정 때 예상 몫)`을 이탈자 예상 분담금 상한 합계 내에서 보전하며, `compensationBurdens`가 여러 노쇼자에게 각자 상한 비율로 청구한다. `NoShowService.open`의 **원래 결제자 `noCompensation=true` 예외도 유지**한다. 모든 부재가 최종 CONFIRMED 판정을 받고 보전액이 전액 회수되며 승계자가 수락한다는 가정이다. 전원 부재는 취소로 처리해 청구/보전 0원이다. 생존자 순부담 합+노쇼자 보전 부담 합=실제 청구액, 크레딧 합=보전 청구 합, 개인 상한·결제자 면제·멱등성을 검사한다. 노쇼자당 평균/P90 보전액, 보전 청구 비율, 일별 총보전액도 출력한다.
- **오차**: C5 한국 주 분포 277,978개의 SHA256을 검증한다. 공유 실제액과 개인 단독 실제액에 독립 추출하고 정산에만 적용한다. 1/N도 동일 일행·실제액·원래 결제자를 사용하고 자체 예상 분담금으로 선결제·보전 상한을 만든다. 노쇼 이후 결제자는 남은 마지막 하차자다. 추가 결제는 `PaymentLifecycleService.finalizeSettlement`처럼 **현재 비결제자인 탑승자**를 분모로 하며 승계한 현재 결제자는 제외한다. 보전 전후 모두 같은 분모를 사용한다. 노쇼자의 보전 청구는 탑승자 추가 결제와 별도 지표다.
- **통계**: 모든 일별 지표 평균의 CI는 **일(day) 단위 복원추출 10,000회·고정 시드 20261119의 부트스트랩 백분위(2.5%, 97.5%, 선형 보간)**로 통일했다. 개인/일행을 독립 표본처럼 다시 추출하지 않는다. 발생확률 지표의 CI는 구성상 0~100% 안에 있으며 사후 절단하지 않는다. 절약률·순부담 변화율은 발생확률이 아니므로 정당한 음수 값을 유지한다. 조건부 지표는 null 일자를 제외한 유효 일수 `valid_days`를 공개한다. 상수 표본은 10,000회가 모두 같은 값인 정확한 퇴화 분포를 사용한다. 드문 손해 표본의 0% 하한·퇴화 구간이 실제 위험 0을 입증하지 않는다. 노쇼 생존자 부담 변화는 같은 실제 청구액에서 노쇼가 없을 때의 분담금을 기준으로 하며, 확정 때 예상 분담금 대비 변화도 별도 기록한다.
- **자료 확보**: 포천 승차장 자료는 로그인·CAPTCHA 없이 23곳을 수집했으나 주소만 있고 좌표는 없다. 대진대/선단동/송우리 후보 명칭을 참고로 기록하며 경로 호출을 늘리지 않는다. 경기 정류소는 반환 HTML이 중간에서 끊겨 CSV/API 방식·인증키 필요 여부·시간대 열을 확인하지 못했다. 새 카카오 호출은 0회다.
- **검증**: 요청=매칭+만료+미납, 요청 중복 없음, 두 정산·보전 전후 금액 보존, 사용자 설정 전파를 Java 어댑터에서 검사한다. 실제 보전 서비스에 대해 단일/복수 노쇼·충분/부족 상한·원래 결제자 면제·멱등성 검증을 수행한다. 모든 발생확률 지표의 CI 범위도 검사한다. `--verify-c6`는 기존 C6 한국 캐시 768요청과 1,000회 한국 오차 부트스트랩을 다시 실행해 기존 수치와 비교하고 C6 공개 파일을 수정하지 않는다. 회차 소요시간 1,000ms 이상은 보수적 탐색 한도 경고로 공개한다. C7 실행 중 Git 명령은 호출하지 않는다.

산출물은 `reports/c7-detailed-sim.json/.md/.csv`, 일별 집계 `c7-detailed-sim-days.csv`, 출처 URL·크기·SHA256·생산 소스/의존성 해시 `c7-input-MANIFEST.json`이다. Markdown에 요청수별·지연별·절약 기준별 발표용 수치 표가 있고, CSV는 `name/metric/mean/ci95_low/ci95_high/valid_days`를 가진 long 형식이다. 원시 생성 요청·확정 일행·생성 개인 지표와 실행 로그·C6 회귀 결과는 Git 제외 `.cache/c7/`에 둔다. 모의 600일을 관측 학생 이용 실적으로 세지 않는다.

## 결과·공개 범위

- `reports/*.csv`: 사례별 집계. 개인 정보·좌표·요청별 결과 없음.
- `reports/*.json`: 소스/의존성/자료 해시, 코드 버전과 dirty 여부, 설정, 집계, 민감도 상태.
- `reports/*.md`: 발표용 설명과 자료 한계.
- `baseline/`: 기존 실험을 재사용한 근거와 재현 방식.
- `.cache/`, `private/`: Git 제외. 원본·영수증·API 키를 제출 묶음에 포함하지 않는다.

공개자료 수집기만 공식 포털·TLC에 HTTPS로 접속한다. 매칭·정산 실험의 신규 **카카오 API 호출은 0회**다. 공개 파일 다운로드를 카카오 호출량으로 세거나 과거 호출 장부를 초기화하지 않는다. 유료 경로 API 수집을 추가하면 실패 요청을 포함한 지속적 장부와 기존 100회 상한을 먼저 구현해야 한다.

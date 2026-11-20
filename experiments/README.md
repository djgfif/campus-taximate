# 한국 공개자료 기반 캡스톤 모의실험

실제 앱의 매칭·검증·정산·보전 코드를 현재 checkout에서 재컴파일한다. 배포 실험이 아니며 학생 이용 실적을 수집하지 않는다. 원본은 Git 제외인 .cache 안에만 저장한다. 이번 OSRM 보정은 Kakao 미래경로 API 400건을 사용했고, C7 엔진 실행 단계의 새 API 호출은 0회다. Python 3.10+, DuckDB, JDK 21이 필요하다.

## 자료 구성

- C5 요금 비교: 한국교통안전공단 TIMS 2025 택시운행정보 격자별 영업통계(500m 격자×월), data.go.kr 15165682. 전국·대진대 5~25km 통학권·기존 캐시 중 포천 목적지 격자를 비교한다. 포천 하위 격자는 포천 전체를 대표하지 않는다. 기존 연간 격자는 선택·보조 비교로 구분한다.
- C7 수요: 서울시·KT 수도권 생활이동 목적별 OA-22300, 출발-도착지 기준. 2026년 10월 포천 출발(41650000), 공휴일 10/5·10/9를 제외한 월~금 20일, 귀가 목적 코드 3을 사용한다. 10/31은 토요일이므로 집계 제외하고 manifest만 기록한다. 10/5 개천절 대체공휴일도 집계에서 제외하고 사유와 해시를 manifest에 남긴다.
- 연령 기술통계: OA-22299의 포천 출발 2030/전체 이동량을 목적지·시간별로 비교한다. 목적 열이 없으므로 귀가·학생 비율로 해석하거나 수요 가중치에 쓰지 않는다.
- C6 경로: 인접 저장소 taximate-matching-sim/realdata의 기존 Kakao 단독·구간 캐시를 고정 코호트로 유지한다.
- C7 경로: 전체 TIMS 500m 격자를 후보로 60km 서비스 범위와 누적 가중치 99% 기준을 적용한 4,311개 중심점을 사용한다. OSRM MLD/car의 모든 방향별 도로 구간을 계산하고 Kakao 미래경로 400건의 훈련 OD 중앙 시간비 1.5670을 적용한다. 기존 118점에 의존하지 않는다.

생활이동 출처 표기: **출처: 서울특별시·KT, 서울시 통신데이터**. 분석 결과는 출처와 함께 공개하며 ZIP·원본 행은 보고서에 복사하거나 커밋하지 않는다.

## 재현 명령

저장소 루트에서 실행한다. 이 PC의 기본 Python은 MSYS이므로 DuckDB 분석과 pytest에는 저장소의 격리 CPython을 사용한다.

~~~powershell
# 기존 파일은 재다운로드하지 않고 ZIP CRC와 SHA256을 검사한다.
node experiments/acquire-public.mjs all
# 선택 가능: tims / living-move / c7 (c7은 생활이동만)
./experiments/.cache/venv/python.exe experiments/livingmove.py
./experiments/.cache/venv/python.exe experiments/tims_regions.py --download
./experiments/.cache/venv/python.exe experiments/fares.py --public
./experiments/.cache/venv/python.exe experiments/run.py --java-home C:/Users/gkho0/.jdks/ms-21.0.12.1 --legacy-realdata ../taximate-matching-sim/realdata --limit 60 --output-prefix korea-cached
./experiments/.cache/venv/python.exe experiments/run.py --java-home C:/Users/gkho0/.jdks/ms-21.0.12.1 --output-prefix synthetic
# Claude가 준비한 전체 교차표(livingmove-sgis-crosswalk.csv)를 먼저 둔다.
./experiments/.cache/venv/python.exe experiments/osrm_matrix.py --crosswalk experiments/.cache/kr-public/livingmove-sgis-crosswalk.csv
./experiments/.cache/venv/python.exe experiments/kakao_calibration.py sample --n 400
./experiments/.cache/venv/python.exe experiments/kakao_calibration.py query
./experiments/.cache/venv/python.exe experiments/kakao_calibration.py fit
./experiments/.cache/venv/python.exe experiments/detailed.py --days 30 --verify-c6 --java-home C:/Users/gkho0/.jdks/ms-21.0.12.1
./experiments/.cache/venv/python.exe experiments/compare_c7.py
./experiments/.cache/venv/python.exe -m pytest experiments/tests
~~~

환경이 없으면 일반 Windows CPython 가상환경에서 requirements-public.txt를 설치하고 pytest를 설치한다. 공개 수집기는 acquire_public.py가 담당하며 acquire-public.mjs는 PUBLIC_PYTHON 또는 .cache/venv/python.exe로 실행한다. 생활이동 다운로드는 서울 공식 파일서버에 multipart POST(infId, seq=YYMMDD, infSeq=1, seqNo 빈 문자열)를 사용한다. 기존 ZIP은 건너뛰고 CRC 검증한다. CAPTCHA가 필요한 포털 자료는 중단한다.

run.ps1은 run.py의 PowerShell 실행 래퍼다. --build는 Maven 서버 패키지를 만들지만 이 작업에서는 기존 server/target/server-0.1.0.jar의 의존성만 읽으며 앱 소스는 직접 재컴파일한다. 클래스와 의존성 복사본은 experiments/.cache 안에 둔다. C7은 --boot-package로 다른 패키지의 의존성도 지정할 수 있다. --only는 진단용이며 공개 결과를 쓰지 않는다. --reuse-output은 입력·생산 코드·출력 해시를 검증한 뒤 집계만 재생한다.

## 생활이동 집계와 가중치

livingmove.py는 ZIP에서 포천 출발 행만 스트리밍하고 DuckDB로 date,d_code,hour,purpose,cnt를 집계하여 .cache/kr-public/living-move-pocheon.csv에 쓴다. 개인 원본은 디스크에 풀지 않는다. 1~2자리 시각은 시간 코드, 3~4자리는 HHMM이며 분은 00/20/40만 허용한다. 예: 16→16시, 1720→17시, 0800→8시. cnt의 *·빈 값은 결측으로 제외하며 0으로 대체하지 않는다. 연령은 2030_cnt와 total_cnt가 함께 공개된 동일 셀만 집계한다. JSON에 파일별 마스킹 수와 관측 시간 코드를 공개한다.

sgis_mois_crosswalk.py가 만든 전체 교차표는 SGIS 2025Q2 각 다각형의 내부 대표점을 Kakao coord2regioncode로 확인한 H 코드와 이름을 보존한다. 생활이동 250개 코드 모두를 해석하며 서울은 자치구(앞 5자리+000)로 합친다. INCHEON_2026과 HWASEONG_2026은 SGIS 다각형과 MOIS 코드 양쪽의 합집합을 하나의 지역으로 처리하므로 244개 지역이 된다. 생성 코드와 출력 CSV의 SHA256은 OSRM manifest와 baseline/PROVENANCE.md에 기록한다.

격자 가중치 = 지역 귀가 흐름 비중 × 해당 격자의 TIMS 2025 승차량 / 그 지역 전체 TIMS 승차량이다. 모든 지역의 209,563개 양수 격자를 후보로 읽는다. 허브 도로거리 60km 밖 격자는 원래 질량을 제외량으로 기록한다. 서비스 가능 격자 가중치의 최소 내림차순 접두합으로 99% 이상을 대표한다. 이는 서비스 밖 지역을 포함한 전국 흐름의 99%라는 뜻이 아니다. 선택된 4,311개 격자는 전체 귀가 흐름의 93.9855%, 서비스 가능 흐름의 99.00003%를 대표한다. 지역 전체 제외 195곳(3.2792%), 일부 지역 내 제외 격자를 합친 서비스 제외 5.0652%, 선택 꼬리 제외 0.9493%를 별도로 공개한다.

TIMS 500m 중심만 사용하며 jitter는 없다. OSRM snap >300m 목적지는 82개이며 최대 1,538m로, 중심점과 실제 도로 종점의 차이가 남는다. 단독요금은 보정 도로시간과 원래 도로거리에 포천 낮 요금 공식을 적용한다. C7 전용 C7RoadMatrix는 18,593,344개 구간을 약 223MB의 연속 배열에 읽고 C6 로더를 바꾸지 않는다. 목적지와 시간은 독립 추출하고 시간은 귀가 출발 분포의 12:00~21:59를 정규화한다.

## 오차와 민감도

C5는 관측 평균 요금과 평균 거리·시간을 포천 낮 요금 공식에 넣은 예상액 사이의 상대 잔차 (실제−예상)/예상을 통학권 격자·월마다 하나씩 만든다. .cache/kr-public/tims-errors.csv와 그 해시·요약은 public-fares.json에 기록한다. TIMS 원본은 2025이며 C7 수요는 2026년 10월이다. 공식 근사는 총시간−15km/h 주행시간을 저속시간으로 간주하고, 기본거리 이후 거리·시간 tick을 더한다. 정확한 미터 재구성은 아니다.

오차 편차 배수는 평균 m을 유지하는 m+s(ε−m), s=1/1.5/2이며 무오차 조건은 ε=0이다. C6은 공유·각 단독 요금에 독립 오차를 주고 비양수 요금이 생기는 일행-반복을 제외해 수를 공개한다. C7은 배수별 비양수 잔차를 제외한 양수 조건부 분포에서 공유·단독을 독립 추출하며 제외 표본 수를 기록한다. 두 방식의 조건부 분모가 다르다. 꼬리값은 임의로 절단하지 않는다.

C6은 예상액으로 확정된 일행을 고정해 각 배수에서 1,000회 정산한다. 두 정산은 같은 일행·실제액·결제자를 사용하고 실제 SettlementCalculator와 PaymentService.prepayAmount를 호출한다. 추가 결제·환불은 비결제자, 절약은 확정 매칭자 기준이다. 매칭률과 지연은 고정이다. C6 입력은 기존 한국 캐시 60사례와 가상 요청을 따로 보고한다.

C7은 기본 하루 100건을 가정한다. 참여율 50/100/150%는 하루 50/100/150건이며 관측 이용자 수가 아니다. 기존 요청수 50/100/200/400×지연 10/15/20 격자와 절약 5/10/15%, 즉시 0/30/60%, 노쇼 5%, 미납 5%, 무오차 조건을 유지하고 편차 1.5/2배 및 150건 조건을 추가한다. 포천 내부 흐름 제외 조건도 더해 총 24조건×30일이다. 각 일자 시드는 독립이고 같은 일자의 조건 간 난수는 공유한다. 오차·노쇼만 바뀌면 같은 매칭을 재사용한다. 1분 회차·5분 예약 규칙, 기본 절약 10%·지연 15분, 걷기 0·단일 허브·동일 성별 풀 가정이다.

C7 보전은 실제 CompensationService.apply를 메모리 저장소에 연결한다. 원래 결제자 면제, 예상 분담금 상한, 여러 노쇼자 상한 비율, 승계자 수락과 전액 회수를 가정한다. 보전 전 raw_와 보전 후 순부담을 구분한다. 추가 결제는 현재 비결제자인 탑승자 기준이다. 전원 부재는 취소다. 요청 회계, 정산 합계, 보전 장부, 중복 사용과 설정 전파를 검사한다. 일별 평균의 CI는 일 단위 10,000회 부트스트랩이며 null 일자는 valid_days에서 제외한다.

## 산출물

- reports/living-move-summary.json/.md: 귀가·전체 목적별 목적지/시간 비중, 일별 변동, 2030 기술통계, 44개 ZIP의 SHA256·크기·URL.
- reports/public-fares.json/.md, public-data-MANIFEST.json: C5 TIMS 비교·잔차·출처.
- reports/c6-error-sensitivity.json/.md: C6 배수별 두 정산 지표와 고정 매칭 지표.
- reports/c7-detailed-sim.json/.md/.csv, c7-detailed-sim-days.csv, c7-input-MANIFEST.json: 24조건 집계·CI·입력/생산 코드 해시.
- .cache/c7: 원시 모의 요청·개인/일행 출력·실행 로그(비공개). 원본 통신 이동행을 공개 보고서로 내보내지 않는다.

## 해석 한계

- **실제 합승 성과 미검증**. 모의 요청·일수·반복 정산을 학생 이용 실적이나 관측 표본 수로 세지 않는다.
- **생활이동은 통신 기반 추정치이며 포천은 시군구 단위라 캠퍼스 단위가 아니다.** 이용자별 독립성, 반복 이동, 추정·마스킹 편향을 해소하지 못한다.
- **귀가 목적이 학생 하교를 대표한다는 가정**이다. 2030 비율도 목적 구분 없는 기술통계이며 학생 비율이 아니다.
- **TIMS 잔차는 집계 평균이라 개별 오차를 과소추정**한다. 편차 배수만으로 실제 개인 오차 분포를 복원하지 못한다.
- 60km 서비스 범위·가중치 꼬리 밖 흐름은 제외·재정규화한다. 목적지×시간 상관과 시간대별 교통은 재현하지 않는다. 개편 그룹 내부 세분화와 500m 격자 내 위치도 재현하지 않는다.
- C6 기존 행렬에는 관측 구간 합성이 포함된다. C7은 OSM/OSRM 경로를 쓰지만 다중경유 요금은 기존 C6 보정 모델을 유지해 새 포천 단독요금과의 모델 차이가 남는다. Kakao 미래 견적도 실제 미터 결제가 아니다.
- 검증 79 OD에서 시간 MAPE 32.71%→22.69%, 거리 11.93%, 요금 18.52%다. 전역 보정은 30km 이상에서 시간 MAPE를 19.31%→27.20%로 악화시켰다. 검증 자료로 재조정하지 않았다.
- 노쇼 조사·회수 실패·운행 경로 단축·결제 상태 머신 전체는 미모형화다. 회차 탐색의 벽시계 제한 때문에 기계 부하에 따라 결과가 달라질 수 있다.

## TIMS 시군구 배정과 공간 커버리지

원본 tims_2025.zip의 12개 월별 CSV 헤더를 모두 확인했으며 행정구역 열은 없다. tims_regions.py는 TIMS 격자 ID의 한글 두 자리(100km)와 숫자 각 세 자리(500m 인덱스 000~199)에서 EPSG:5179 UTM-K/GRS80 중심점을 계산한다. 기준은 E=700000,N=1300000이고 중심점은 좌하단에 250m를 더한다. 기존 120개 비허브 점은 격자 안에서 난수 추출된 점이며, 역투영 좌표가 자기 격자 안에 들어가는지도 검증한다.

시군구 경계는 국가데이터처 SGIS의 공식 2025년 2분기 자료(data.go.kr 15129688, BASE_DATE=20250630)를 쓴다. 공식 PRJ는 ESRI:102080 명칭을 사용하므로 EPSG 코드 이름을 단정하지 않고 GRS80 UTM-K 투영 파라미터 일치와 pyproj 변환을 검증한다. 격자 중심점과 공식 폴리곤을 공간 결합하며 최근접 캐시 점·최근접 폴리곤으로 보충하지 않는다. 경계 밖과 다중 소속 격자는 미배정으로 기록하고, 캐시 목적지 격자가 미배정이면 실행을 중단한다. 기존 tims_regions.py의 8개 지역 교차표는 역사적 캐시 검사용이다. C7은 sgis_mois_crosswalk.py의 전체 교차표와 개편 그룹을 사용하며 코드 숫자 모양으로 추측하지 않는다.

출처: 국가데이터처(통계청) SGIS, 2025년 2분기 센서스용 행정구역 경계. 원본 ZIP은 .cache/kr-public에만 저장한다. 배정 규칙·PRJ·원본 및 사용 멤버 SHA256·전국 미배정 건수·영업건수는 reports/tims-region-MANIFEST.json, 시군구별 흐름·커버리지·대표 비중·유실 비중은 c7-detailed-sim.json의 demand.region_coverage에 있다. C7은 캐시 점 존재 여부로 지역을 제외하지 않는다. 서비스 범위와 누적 선택 꼬리만 별도 집계한다.

C7은 포천 전체의 양수 TIMS 격자를 후보로 삼는다. 지역 귀가 수요가 TIMS 택시 승차 분포를 따른다는 가정, 경계에 걸친 격자를 중심점 한 곳에 배정하는 오차, SGIS 센서스 경계와 실제 행정 경계 차이, 한 시점의 경계를 연간 통계에 적용한 차이가 남는다. 기본 하루 100건은 선택된 목적지 분포를 조건으로 한 가정이며 시 전체 이용량의 추정이 아니다.

exclude_intra_pocheon 민감도는 포천 목적지 확률을 0으로 하고 나머지를 재정규화한다. 하루 100건과 귀가 시간 분포·오차는 유지한다. 이는 같은 요청 수에서 목적지 구성을 바꾼 비교이며 외부 통학 이용자 수 추정이 아니다. C6의 고정 일행·TIMS 잔차·생산 Java 입력이 같으면 C6 수치를 다시 계산하지 않고 해시 불변 여부만 검증한다.

참고: [TIMS 원본](https://www.data.go.kr/data/15165682/fileData.do), [공식 2025Q2 경계](https://www.data.go.kr/data/15129688/fileData.do), [한국교통연구원 격자체계 분석](https://www.ktdb.go.kr/DATA/pblcte/20230613101540605.pdf).

## 최신 실행 결과 (2026-11-20)

24조건×30일, 105,000요청을 실행했다. 정산 합계 오류·경로 불가·탐색시간 경고는 모두 0이다. C6 60사례와 1,000회 민감도는 기존 결과와 동일했다. pytest 61개와 9 subtests가 통과했다.

- [전체 결과](reports/c7-detailed-sim.md), [기존 118점 대비 핵심 표](reports/c7-grid-comparison.md)
- [지역별 범위·유실](reports/c7-osrm-region-coverage.csv), [snap >300m](reports/c7-osrm-snaps-over300.csv), [보정 검증](reports/c7-osrm-calibration.json)
- [실행 명령·해시·한계·절대 경로 인계서](reports/osrm-step1-handoff.md)

목적지·경로·시간·단독요금 변경이 동시에 반영된 비교다. 차이를 어느 한 변경의 인과효과로 해석하지 않는다. sample은 기존 파일에 다시 실행하지 않고 query로 재개한다. 새 자료/표본은 별도 --output 경로와 detailed.py --routing-cache를 사용한다. API 키는 저장소 .env의 KAKAO_REST_KEY에서 읽으며 보고서에 기록하지 않는다.

## C8: exact optimality benchmark

Run after the completed 30-day C7 pipeline, using the same public-data cache:

```powershell
./experiments/.cache/venv/python.exe experiments/calibration_refinement.py
# If status is ACCEPTED, rerun detailed.py --days 30 --verify-c6 first.
./experiments/.cache/venv/python.exe experiments/optimality_gap.py
./experiments/.cache/venv/python.exe -m pytest experiments/tests -q --basetemp=experiments/.cache/t8
```

Use a fresh, short basetemp path on Windows. C8 extracts the base, n50_d15 and
participation150 requests byte-for-byte from C7. It recompiles unchanged
production sources in its own cache, exhaustively enumerates groups through
production verifiers and solves set packing with unlimited exact subset DP.
No new solver dependency is required. A separate Java replay verifies every
actually selected group.

The calibration compares global, distance-band and nonnegative affine correction
with train-only OD five-fold cross-validation, then applies a frozen held-out
acceptance rule: overall improvement and no band worse by over 2 percentage
points. The 2026-11-20 decision kept the existing model, so C7 was not regenerated.

Results: [gap report](reports/c8-optimality-gap.md),
[JSON](reports/c8-optimality-gap.json),
[calibration selection](reports/c8-calibration-selection.json).
Daily metrics, selected groups, source hashes, per-solve times and logs are under
experiments/.cache/c8. Use --reuse-export to recompute exact policies from a
hash-verified Java export. Production-state round regret is separate from
independent-policy day gaps, which include subsequent queue feedback. Hindsight
relaxes round confirmation while retaining common departure windows; its matched
rider count is an upper bound within this simulated model.


## C9: 하차 설계와 사전예약 비교

C7의 동일한 90개 수요일(50/100/150명, 각 30일)에서 문 앞 하차, K=5/10/20 실제 교통 허브 하차, 마지막 기회 혼합 제안(보행만/보행·택시), 15/30분 사전예약을 비교한다. 사전예약은 즉시요청의 유효 출발창을 예약창으로 표현하므로 0분 전환 대조군 D0도 함께 실행한다.

```powershell
./experiments/.cache/venv/python.exe experiments/dropoff_hubs.py
./experiments/.cache/venv/python.exe experiments/dropoff_designs.py
./experiments/.cache/venv/python.exe -m pytest experiments/tests
# 해시를 확인한 기존 결과에서 보고서만 재생성:
./experiments/.cache/venv/python.exe experiments/dropoff_designs.py --reuse
```

Kakao REST 키는 기존 .env에서 읽고 출력하지 않는다. OSRM car 서버는 http://127.0.0.1:5000 에 있어야 한다. 보행은 대권거리×1.3, 4.5km/h의 가정이며 1.2km를 넘으면 같은 C7 요금모형의 라스트마일 택시를 이용한다. 허브는 수요가중 네트워크 p-median 국소해로 선정하며, 기본 K는 K20 목적함수의 1.25배 이내인 가장 작은 K다. 수용 판단은 예상 견적 기준이고, 사후 harmed는 C7과 같은 TIMS 잔차를 적용한 별도 결과다. 실제 승객 수용은 검증하지 않았다.

[한국어 결과·허브 이름/좌표/배정 수요](reports/c9-dropoff-designs.md), [모든 95% CI·A 대비 대응 차이·해시](reports/c9-dropoff-designs.json). 검색 응답·응답 해시·K별 배정·양방향 OSRM 표는 .cache/hubs, 승객별 정산·일별 결과·생산 소스 해시·실행/테스트 로그는 .cache/c9에 있다. 생산 matching-engine/ 및 server/ 파일은 변경하지 않는다.

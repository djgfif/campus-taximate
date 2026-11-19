# C5 공개 운행 기록 요금 실험

한국 실제 운행 경로의 일반택시 환산 평가 277,978건에서 ±15% 이내 88.39%, +15% 초과 9.45%. 실제 일반택시 결제 검증은 아니다.

발표용 한 문장: 한국 실제 운행 경로의 일반택시 환산 평가 277,978건에서 ±15% 이내 88.39%, +15% 초과 9.45%. 실제 일반택시 결제 검증은 아니다.

## 한국 주 실험: 서울 실제 운행 경로의 일반택시 요금 환산

장애인콜택시의 실제 도로 운행에 일반택시 요금표를 적용한 반사실 실험이다. 일반택시 영수증·카카오 견적 검증으로 표현하지 않는다.

| 방법 | n | MAE | MAPE | 중앙 절대오차율 | ±10% | ±15% | +15% 초과 | 실제<예상 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 장콜 공식 검증: 실제 거리→장콜 요금표 | 369,554 | 50.70 | 2.03% | 0.00% | 91.64% | 99.51% | 0.49% | 0.00% |
| 장콜 경로 예측: 학습 거리→장콜 요금표 | 277,978 | 83.84 | 3.40% | 0.00% | 84.31% | 93.97% | 5.57% | 3.53% |
| 장콜 실제 요금: 학습 OD 요금 중앙값 | 277,978 | 55.57 | 2.27% | 0.00% | 87.69% | 96.27% | 3.16% | 5.65% |
| 한국 발표 주 지표 (i): 일반택시 환산 | 277,978 | 578.23 | 6.20% | 3.70% | 78.25% | 88.39% | 9.45% | 28.88% |

학습 2025-01..09 1,105,795건, 평가 유효 369,554건. 예측 범위: {"od_timeband": 230034, "insufficient_training": 91576, "od_only": 47944}.
장콜 공식은 기본 5km 1,500원, 5~10km 280원/km, 이후 70원/km, 100원 미만 절사이며 시간·지역 할증이 없다. 실제 결제 잔차가 0이라고 가정하지 않는다.

| 일반택시 환산 주 지표 분해 | n | MAPE | ±15% | +15% 초과 |
|---|---:|---:|---:|---:|
| 거리 km 0.1-3 | 79,820 | 6.30% | 89.11% | 6.27% |
| 거리 km 3-10 | 144,650 | 6.36% | 87.62% | 11.13% |
| 거리 km 10-30 | 49,138 | 5.61% | 89.29% | 9.69% |
| 거리 km 30-160 | 4,370 | 5.39% | 90.62% | 9.11% |
| 시간대 0 | 2,101 | 4.36% | 93.91% | 3.43% |
| 시간대 1 | 100,329 | 5.82% | 89.93% | 7.99% |
| 시간대 2 | 129,440 | 6.39% | 87.69% | 10.26% |
| 시간대 3 | 17,248 | 7.14% | 84.66% | 12.63% |
| 시간대 4 | 813 | 4.55% | 93.97% | 4.55% |
| 시간대 5 | 10,848 | 5.32% | 90.70% | 7.01% |
| 시간대 6 | 14,169 | 6.90% | 85.79% | 11.60% |
| 시간대 7 | 3,030 | 6.53% | 87.36% | 9.41% |

## TIMS 한국 일반택시 보조

월별 원본 상태: COMPLETED. 아래 주 비교는 **격자-월 평균**이다. 연간 격자 캐시는 JSON에 별도로 보존한다.

| 방법 | n | MAE | MAPE | 중앙 절대오차율 | ±10% | ±15% | +15% 초과 | 실제<예상 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| commute / seoul_gyeonggi_standard | 41,391 | 4425.67 | 23.63% | 30.84% | 4.80% | 11.54% | 88.34% | 0.35% |
| commute / pocheon | 41,391 | 2015.42 | 10.81% | 9.96% | 50.27% | 71.05% | 22.80% | 25.96% |
| national / seoul_gyeonggi_standard | 1,667,126 | 4705.35 | 23.86% | 29.27% | 17.25% | 24.87% | 73.74% | 5.56% |
| national / pocheon | 1,667,126 | 2512.71 | 14.39% | 11.37% | 44.85% | 61.98% | 28.14% | 36.67% |

- Monthly raw archive is analyzed separately from annual caches; annual values do not reconstruct grid-month errors.
- Nonlinear tariff at an aggregate mean differs from mean trip fares; national regions use different tariffs.
- No low-speed clock, night surcharge, border or toll detail; this is a tariff proxy comparison, not validation of its exact formula.
- Commute grids describe pickups 5-25 km from Daejin, not confirmed Daejin student trips.

## 해외 실제 미터 요금 참고: NYC Yellow

학습 2026-08, 평가 2026-09. 실제액은 fare_amount만 사용하며 팁·세금·통행료·surcharge를 제외한다.

| 방법 | n | MAE | MAPE | 중앙 절대오차율 | ±10% | ±15% | +15% 초과 | 실제<예상 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| c 먼저: 실제 경로→요금표 하한 근사 | 2,356,923 | 1.64 | 7.49% | 6.83% | 67.87% | 82.14% | 17.22% | 16.96% |
| c 참고: 거리+전체시간 상한 근사 | 2,356,923 | 6.49 | 37.80% | 27.32% | 0.10% | 0.92% | 0.00% | 99.99% |
| a: 이전 월 OD 경로→요금표 하한 근사 | 2,309,750 | 3.89 | 21.74% | 20.74% | 27.34% | 37.23% | 48.84% | 25.79% |
| b: 이전 월 OD 실제 요금 중앙값 | 2,309,750 | 3.15 | 19.63% | 16.28% | 34.46% | 46.64% | 35.40% | 36.67% |

c의 MAPE 7.49%는 저속 clock 부재와 데이터/미터 재구성 잔차를 포함한다. a의 MAPE 21.74%를 순수 경로 예측 오차로 해석할 수 없다.
fare_amount 외 extra, mta_tax, improvement_surcharge, congestion_surcharge, cbd_congestion_fee, Airport_fee, tip_amount, tolls_amount는 별도 열이며 비교 금액에 더하지 않는다.

## 오차 원인 분해 (같은 평가 표본)

아래는 log(금액 배수)의 평균이다. 세 요인 합이 총 log 오차이며 MAPE를 더한 값이 아니다. 시간 항은 교통 변동을 포함하지만 인과적으로 교통 효과만 분리한 값은 아니다.

| 비교 | n | 미터 재구성 | 거리 예측 | 시간·상호작용 | 총 log 오차 |
|---|---:|---:|---:|---:|---:|
| 한국 장콜 실제 요금 | 277,978 | 0.020516 | 0.009181 | 0.000000 | 0.029697 |
| 한국 일반택시 환산 | 277,978 | 0.000000 | 0.013378 | 0.011798 | 0.025176 |
| NYC a | 2,309,750 | 0.046683 | 0.015294 | 0.067598 | 0.129575 |

일반택시 환산의 미터 재구성 항이 0인 것은 양쪽에 같은 근사식을 적용한 정의의 결과다. 실제 일반택시 미터 정확도를 입증하지 않는다.

## 제외·예측 범위

| 자료 | 입력 | 유효 | 제외 | 사유 |
|---|---:|---:|---:|---|
| nyc_train | 3,336,716 | 2,124,954 | 1,211,762 | {"invalid_time_order": 39423, "missing_or_unknown_od": 6716, "distance_outside_0.1_160km": 18592, "duration_outside_1_180min": 2490, "pickup_outside_period": 15, "invalid_fare_or_missing_numeric": 13331, "non_standard_rate": 1130864, "speed_outside_1_130kmh": 331} |
| nyc_test | 3,876,044 | 2,356,923 | 1,519,121 | {"distance_outside_0.1_160km": 21094, "duration_outside_1_180min": 2540, "missing_or_unknown_od": 7160, "pickup_outside_period": 21, "invalid_time_order": 46588, "non_standard_rate": 1427445, "speed_outside_1_130kmh": 680, "invalid_fare_or_missing_numeric": 13593} |
| korea | 1,729,476 | 1,475,349 | 254,127 | {"speed_outside_1_130kmh": 129, "distance_outside_0.1_160km": 6687, "duration_outside_1_180min": 328, "pickup_outside_period": 19, "invalid_fare_or_missing_numeric": 246962, "canceled": 2} |

OD 최소 표본은 사전 고정 20건. 평일/주말×6시간대(8칸), 부족하면 같은 OD 전체 시간으로 후퇴, 그래도 부족하면 평가 제외. 모든 평가는 시간상 뒤의 자료만 사용한다.
MAPE 분모는 실제액, 상대오차·임계값 분모는 예상액이다. 상세 백분위·거리대·시간대 지표, 파일 URL/크기/SHA256/행 수는 동명 JSON에 있다.

## 해석 한계

- 한국 주 지표는 실제 경로의 일반택시 환산액이며 일반택시 실제 결제액이 아니다. 장콜 실제 요금은 별도 보조 지표다.
- 저속·정차 시간 미공개. 서울은 총시간−15km/h 주행시간으로 근사하며 기본거리 이내 시간요금은 제외한다. 승차 시각 할증률을 전 구간에 고정하고 경계 통과 할증·통행료를 제외한다.
- NYC max(거리 tick,시간 tick)은 혼합 속도 주행에서 미터 tick의 하한 근사다. c는 요금표 공식 자체 정확도가 아닌 자료 부족을 포함한 재구성 오차다. 거리+전체시간 상한 근사도 함께 공개하되 결과에 맞춰 주 공식을 바꾸지 않는다.
- 서울 장콜 공식은 5km 1500원, 5~10km 280원/km, 이후 70원/km, 시간/지역 할증 없음, 100원 미만 절사다. 주차·통행료는 승객 부담이나 데이터에 별도 열이 없어 잔차 원인을 확정할 수 없다.
- NYC 표준 요금표는 2022-12-19 이후 기본 $3, tick $0.70. 12mph 초과 거리 또는 저속 분당 중 하나를 부과한다.
- OD 구역/동의 범위가 넓고 휴일은 주말로 대체한다. 이전 기간 경로 중앙값은 카카오 알고리즘이 아니다. 이질적 장콜/NYC 오차를 포천 동승에 옮기는 것은 스트레스 가정이다.
- 반복 이용자 식별자가 없어 사용자 단위 독립성·중복 운행을 확정할 수 없다. 모집단 파일 전체를 사용하되 필터/OD 커버리지 편향이 남는다.
- 추가 결제·환불 C5 비율은 운행 요금 비교다. 결제자 제외·원 단위 반올림을 적용한 개인 비율은 C6에서 따로 계산한다.

## 추가 한국 자료 확인

- 서울시 OA-13550 일별 이용현황 https://data.seoul.go.kr/dataList/OA-13550/L/1/datasetView.do : 일별 평균/집계이므로 OD 개별 평가에 미사용.
- 서울시 OA-13551 목적지 BEST100 https://data.seoul.go.kr/dataList/OA-13551/L/1/datasetView.do : 순위 집계이며 개별 거리·실제 요금 쌍이 없어 미사용.
- 경기 광역이동지원센터 https://ggsts.gg.go.kr/ : 개인 이용내역 조회 서비스만 확인; 공개 개별 OD/거리/요금 파일을 확보하지 못해 미사용.
- 인천교통공사 https://www.ictr.or.kr/main/openinfo/public_list.jsp 및 https://www.ictr.or.kr/main/bbs/bbsMsgDetail.do?bcd=opendata&msg_seq=4011 : 월별 접수·승차·수입액 및 분기 이용실적 집계로 공개하며 개별 OD·요금 쌍이 아니어서 미사용.
- 대구 나드리콜 https://www.data.go.kr/data/15125690/openapi.do 및 https://nadrihome.dpfc.or.kr/bbs/board.php?bo_table=notic&wr_id=254 : 운영통계/API 제공 범위를 확인했으나 공개 운행 단위 OD·실제 요금 파일을 확보하지 못함. 서울 원본과 동등한 검증 입력으로 채택하지 않음.

## 출처

- seoul_trips: https://www.data.go.kr/data/15115859/fileData.do
- seoul_tariff: https://news.seoul.go.kr/traffic/archives/1659
- special_taxi_tariff: https://www.sisul.or.kr/open_content/calltaxi/introduce/receipt.jsp
- gyeonggi_tariff: https://gits.gg.go.kr/web/newboard/5567.do?idx=189653336&mode=view
- pocheon_tariff: https://www.pocheon.go.kr/www/contents.do?key=3878
- tims: https://www.data.go.kr/data/15165682/fileData.do
- nyc_trips: https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page
- nyc_tariff: https://www.nyc.gov/site/tlc/passengers/taxi-fare.page
- nyc_lookup: https://d37ci6vzurychx.cloudfront.net/misc/taxi_zone_lookup.csv
- nyc_dictionary: https://www.nyc.gov/assets/tlc/downloads/pdf/data_dictionary_trip_records_yellow.pdf

# C6 공개 오차 분포 반영 모의 운영

TIMS 집계 잔차를 정산에 적용하면 비율 정산 평균 실제 절약률 48.37%→47.44%, 손해 비율 2.61%, 비결제자 추가 결제 22.76%. 합계 오류 0건. 모의실험 결과다.

발표용 한 문장: TIMS 집계 잔차를 정산에 적용하면 비율 정산 평균 실제 절약률 48.37%→47.44%, 손해 비율 2.61%, 비결제자 추가 결제 22.76%. 합계 오류 0건. 모의실험 결과다.

| 입력 | 오차 자료 | 조건 | 정산 | 평균 실제 절약률 | 손해 비율 | 추가 결제(비결제자) | 환불(비결제자) | 평균 환불 원 | 청구 차이 절댓값 평균(원) | 추가결제 발생시 평균(원) | 합계 오류 | 비양수 배수로 제외된 일행-반복 |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| korea-cached | korea_primary | epsilon_0 | ratio | 48.37% | 0.00% | 0.00% | 100.00% | 2105.59 | 0.00 | 0.00 | 0 | 0 |
| korea-cached | korea_primary | epsilon_0 | equal | 47.50% | 1.21% | 0.00% | 100.00% | 2284.24 | 0.00 | 0.00 | 0 | 0 |
| korea-cached | korea_primary | empirical | ratio | 47.44% | 2.61% | 22.76% | 77.21% | 1878.53 | 4694.73 | 1218.71 | 0 | 0 |
| korea-cached | korea_primary | empirical | equal | 46.56% | 3.92% | 22.76% | 77.19% | 2037.87 | 4694.73 | 1321.94 | 0 | 0 |
| korea-cached | korea_primary | error_x1_5 | ratio | 46.18% | 5.15% | 31.02% | 68.96% | 2526.09 | 6493.41 | 1850.52 | 0 | 154 |
| korea-cached | korea_primary | error_x1_5 | equal | 45.27% | 6.31% | 31.03% | 68.96% | 2739.78 | 6493.41 | 2007.48 | 0 | 154 |
| korea-cached | korea_primary | error_x2 | ratio | 44.04% | 8.17% | 35.73% | 64.25% | 3204.81 | 8369.48 | 2509.04 | 0 | 295 |
| korea-cached | korea_primary | error_x2 | equal | 43.10% | 9.16% | 35.74% | 64.25% | 3476.08 | 8369.48 | 2722.07 | 0 | 295 |
| synthetic | korea_primary | epsilon_0 | ratio | 65.82% | 0.00% | 0.00% | 100.00% | 488.65 | 0.00 | 0.00 | 0 | 0 |
| synthetic | korea_primary | epsilon_0 | equal | 65.39% | 0.00% | 0.00% | 100.00% | 514.71 | 0.00 | 0.00 | 0 | 0 |
| synthetic | korea_primary | empirical | ratio | 65.22% | 0.29% | 23.00% | 76.89% | 435.84 | 1395.07 | 269.34 | 0 | 0 |
| synthetic | korea_primary | empirical | equal | 64.77% | 0.38% | 23.02% | 76.86% | 459.13 | 1395.07 | 283.32 | 0 | 0 |
| synthetic | korea_primary | error_x1_5 | ratio | 64.40% | 0.78% | 31.10% | 68.85% | 584.75 | 1928.46 | 417.55 | 0 | 37 |
| synthetic | korea_primary | error_x1_5 | equal | 63.94% | 0.92% | 31.12% | 68.84% | 615.92 | 1928.46 | 439.43 | 0 | 37 |
| synthetic | korea_primary | error_x2 | ratio | 62.98% | 1.70% | 35.79% | 64.16% | 740.96 | 2483.60 | 568.84 | 0 | 85 |
| synthetic | korea_primary | error_x2 | equal | 62.51% | 1.91% | 35.80% | 64.14% | 780.66 | 2483.60 | 598.91 | 0 | 85 |

## 고정 매칭 지표

| 입력 | 요청 | 매칭률 | 예상 비율 절약률 | 지연(분) |
|---|---:|---:|---:|---:|
| korea-cached | 768 | 86.07% | 48.37% | 5.109 |
| synthetic | 192 | 100.00% | 65.82% | 0.423 |

각 조건은 고정 시드로 1,000회 이상 반복한다. JSON에 원본 오차 표본 수·분모·입력/생산 코드 해시를 기록했다.

## 정의

- mean_actual_saving: mean(1-share_i / independent_actual_solo_i), matched riders only
- harmed_rate: share_i > independent_actual_solo_i; denominator all matched riders
- additional_payment_rate: actual share > production prepayAmount(expected share); denominator NON-PAYERS
- refund_rate: actual share < prepaid; denominator NON-PAYERS; mean refund conditional on refund
- bootstrap: 1000 complete fixed-group replays; errors sampled independently for shared and every solo fare; same draws across scenarios and methods
- negative_scaled_fares: Exclude group-replicate if any scaled multiplier <=0; report nonpositive_multiplier_draws and invalid_scaled_groups. Scaled scenario metrics are conditional on positive fares. Positive fares round to integer KRW, minimum 1 KRW.
- equal_comparison: Same groups, payer, actual shared and solo fares; equal expected shares generate their own prepaid amounts

## 한계

- MatchingRound/RouteGroupVerifier run once on expected fares. No error enters matching.
- SettlementCalculator and PaymentService.prepayAmount are compiled from current production sources.
- TIMS 집계 평균 잔차는 개별 운행 오차 분산을 과소추정한다. 실제 합승 성과 미검증.
- 확정 매칭자 기준이며 미매칭자를 절약률 분모에 넣지 않는다. 반복 시행은 학생 이용 실적/관측 표본 수가 아니다.
- 오차 확대 시 비양수 요금이 되는 일행-반복은 해당 민감도 조건에서 제외하고 건수를 공개한다. 평균을 유지한 편차 ×1.5/×2는 양수 요금에 조건부인 지표이며 전체 확대 분포의 무조건 평균이 아니다. 0에 가까운 양수 단독 요금 때문에 평균 절약률의 꼬리 민감성도 크다.

출처: [C5 공개자료 보고서](public-fares.md), https://www.data.go.kr/data/15165682/fileData.do

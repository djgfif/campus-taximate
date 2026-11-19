# C6 공개 오차 분포 반영 모의 운영

한국 경로 환산 오차를 정산에 적용하면 비율 정산 평균 실제 절약률 48.37%→47.86%, 손해 비율 1.42%, 비결제자 추가 결제 9.52%. 합계 오류 0건. 모의실험 결과다.

발표용 한 문장: 한국 경로 환산 오차를 정산에 적용하면 비율 정산 평균 실제 절약률 48.37%→47.86%, 손해 비율 1.42%, 비결제자 추가 결제 9.52%. 합계 오류 0건. 모의실험 결과다.

| 입력 | 오차 자료 | 조건 | 정산 | 평균 실제 절약률 | 손해 비율 | 추가 결제(비결제자) | 환불(비결제자) | 평균 환불 원 | 합계 오류 | 비양수 배수로 제외된 일행-반복 |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| korea-cached | korea_primary | epsilon_0 | ratio | 48.37% | 0.00% | 0.00% | 100.00% | 2105.59 | 0 | 0 |
| korea-cached | korea_primary | epsilon_0 | equal | 47.50% | 1.21% | 0.00% | 100.00% | 2284.24 | 0 | 0 |
| korea-cached | korea_primary | empirical | ratio | 47.86% | 1.42% | 9.52% | 90.45% | 2021.96 | 0 | 0 |
| korea-cached | korea_primary | empirical | equal | 46.98% | 2.81% | 9.52% | 90.42% | 2194.09 | 0 | 0 |
| korea-cached | korea_primary | error_x1_5 | ratio | 47.20% | 2.95% | 16.26% | 83.63% | 2179.51 | 0 | 32 |
| korea-cached | korea_primary | error_x1_5 | equal | 46.31% | 4.23% | 16.26% | 83.56% | 2366.35 | 0 | 32 |
| korea-cached | korea_primary | error_x2 | ratio | 46.35% | 4.54% | 21.32% | 78.66% | 2361.40 | 0 | 163 |
| korea-cached | korea_primary | error_x2 | equal | 45.45% | 5.75% | 21.32% | 78.65% | 2562.09 | 0 | 163 |
| korea-cached | nyc_reference | epsilon_0 | ratio | 48.37% | 0.00% | 0.00% | 100.00% | 2105.59 | 0 | 0 |
| korea-cached | nyc_reference | epsilon_0 | equal | 47.50% | 1.21% | 0.00% | 100.00% | 2284.24 | 0 | 0 |
| korea-cached | nyc_reference | empirical | ratio | 44.15% | 8.69% | 48.89% | 51.10% | 2982.83 | 0 | 0 |
| korea-cached | nyc_reference | empirical | equal | 43.22% | 9.70% | 48.89% | 51.10% | 3236.08 | 0 | 0 |
| korea-cached | nyc_reference | error_x1_5 | ratio | 38.25% | 13.88% | 55.08% | 44.92% | 3952.85 | 0 | 217 |
| korea-cached | nyc_reference | error_x1_5 | equal | 37.22% | 14.61% | 55.08% | 44.92% | 4288.69 | 0 | 217 |
| korea-cached | nyc_reference | error_x2 | ratio | 22.45% | 18.15% | 60.39% | 39.61% | 5132.33 | 0 | 2457 |
| korea-cached | nyc_reference | error_x2 | equal | 21.12% | 18.76% | 60.39% | 39.61% | 5568.65 | 0 | 2457 |
| synthetic | korea_primary | epsilon_0 | ratio | 65.82% | 0.00% | 0.00% | 100.00% | 488.65 | 0 | 0 |
| synthetic | korea_primary | epsilon_0 | equal | 65.39% | 0.00% | 0.00% | 100.00% | 514.71 | 0 | 0 |
| synthetic | korea_primary | empirical | ratio | 65.50% | 0.16% | 9.23% | 90.73% | 467.35 | 0 | 0 |
| synthetic | korea_primary | empirical | equal | 65.06% | 0.19% | 9.23% | 90.72% | 492.31 | 0 | 0 |
| synthetic | korea_primary | error_x1_5 | ratio | 65.08% | 0.41% | 16.04% | 83.83% | 502.94 | 0 | 11 |
| synthetic | korea_primary | error_x1_5 | equal | 64.63% | 0.49% | 16.03% | 83.77% | 530.11 | 0 | 11 |
| synthetic | korea_primary | error_x2 | ratio | 64.51% | 0.74% | 21.10% | 78.87% | 544.11 | 0 | 51 |
| synthetic | korea_primary | error_x2 | equal | 64.05% | 0.87% | 21.10% | 78.86% | 573.23 | 0 | 51 |
| synthetic | nyc_reference | epsilon_0 | ratio | 65.82% | 0.00% | 0.00% | 100.00% | 488.65 | 0 | 0 |
| synthetic | nyc_reference | epsilon_0 | equal | 65.39% | 0.00% | 0.00% | 100.00% | 514.71 | 0 | 0 |
| synthetic | nyc_reference | empirical | ratio | 63.08% | 1.73% | 48.49% | 51.37% | 694.32 | 0 | 0 |
| synthetic | nyc_reference | empirical | equal | 62.61% | 1.94% | 48.59% | 51.37% | 731.47 | 0 | 0 |
| synthetic | nyc_reference | error_x1_5 | ratio | 59.21% | 4.36% | 54.76% | 45.24% | 919.40 | 0 | 67 |
| synthetic | nyc_reference | error_x1_5 | equal | 58.67% | 4.65% | 54.76% | 45.24% | 968.58 | 0 | 67 |
| synthetic | nyc_reference | error_x2 | ratio | 48.69% | 7.93% | 59.98% | 39.90% | 1190.08 | 0 | 658 |
| synthetic | nyc_reference | error_x2 | equal | 48.07% | 8.25% | 60.07% | 39.89% | 1254.25 | 0 | 658 |

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
- 한국 경로 환산/NYC 운행 오차를 포천 동승에 독립 적용하는 외적 타당성은 입증하지 않는다.
- NYC 금액 USD를 KRW로 환산하지 않고 무차원 상대오차만 사용한다.
- 확정 매칭자 기준이며 미매칭자를 절약률 분모에 넣지 않는다. 반복 시행은 학생 이용 실적/관측 표본 수가 아니다.
- 오차 확대 시 비양수 요금이 되는 일행-반복은 해당 민감도 조건에서 제외하고 건수를 공개한다. ×1.5/×2는 양수 요금에 조건부인 지표이며 전체 확대 분포의 무조건 평균이 아니다. 0에 가까운 양수 단독 요금 때문에 평균 절약률의 꼬리 민감성도 크다.

출처: [C5 공개자료 보고서](public-fares.md), https://www.data.go.kr/data/15115859/fileData.do , https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page

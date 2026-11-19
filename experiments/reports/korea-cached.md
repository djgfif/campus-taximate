# 매칭·정산 실험

자료: 기존 한국 캐시 기반 대리 자료. 실제 학생 탑승 효과를 의미하지 않는다.

요청 768건, 매칭 661건, 만료 107건, 매칭률 86.07%.
매칭된 사람의 평균 절감률: 비율 정산 48.37%, 1/N 47.50%.
전체 요청 기준 평균 절감률(미매칭은 0): 비율 41.63%, 1/N 40.88%.
금액 보존 오류 0건. 1/N에서 10% 절감 미달 28명.

두 정산은 같은 매칭 결과와 결제액을 사용한다. 별도 매칭 알고리즘의 우열 비교가 아니다.
현재 앱의 MatchingRound, RouteGroupVerifier, SettlementCalculator를 직접 컴파일했다.
시간은 엔진과 로컬 경로 모델을 포함한다. 네트워크 호출은 0회이며 실제 API 지연을 측정하지 않았다.
실측 요금 오차 민감도는 전향적 실측 자료가 있어야 실행한다. 자료가 없으면 미검증으로 남긴다.

- TIMS aggregate demand proxy; not observed student OD
- request times may be synthetic
- 51.5% matrix cells composed from observed legs
- multi-stop fare modeled from cached solo quotes; not actual meter payment
- legacy report MAPE 14.35% compares model and Kakao, not actual fare accuracy

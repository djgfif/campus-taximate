## C7 실제 실행 산출물 (2026-11-20)

교차표 미해결 문제는 해소됐다. RAW_MATRIX_COMPLETE → CALIBRATED 상태로 실제 로컬 OSRM 행렬과 Kakao 미래경로 400건을 생성했다. 아래 수치는 실측 학생 운행이 아닌 공개자료 대리 수요 실험이다.

- OSRM raw_matrix.tsv SHA256: 3eaabb0dbec380b20e9762622fa9819b2dd6eb24dc9164c086f4bd49cdcfb044
- 보정 matrix.tsv SHA256: 3eaabb0dbec380b20e9762622fa9819b2dd6eb24dc9164c086f4bd49cdcfb044
- Kakao 응답 CSV SHA256: 9bd62fff18c6287f9b5ba68c0a660f1ac59f01c827caf9b99467a3156b30d6aa
- 전체 inputs/output 크기·SHA256: experiments/.cache/osrm/MANIFEST.json 및 reports/c7-input-MANIFEST.json
- 4,311개 격자·대표 흐름 93.9855%, 시간 검증 MAPE 22.6869%, 요금 18.5227%.

전역 시간 보정은 30km 이상 보류 OD에서 오차를 악화시켰다(19.3144%→27.1982%). 82개 목적지의 snap이 300m를 넘어 중심점과 실제 도로 종점 차이가 있다. 교차표 대표점 기반 관계, 개편 그룹 내부 분포, 500m 중심 배정, 미래 견적과 관측 미터의 차이, 다중경유 기존 C6 요금 모형은 여전히 한계다. 기존 118점 핵심 수치와 원본 보고서 SHA256은 reports/c7-old118-headlines.json에 보존한다.

## C7 전체 교차표 및 2026 개편 그룹 반영

Claude가 생성한 `sgis_mois_crosswalk.py`는 SGIS 2025Q2 각 시군구 다각형의 내부 대표점을 Kakao coord2regioncode에 조회해 H 코드 앞 5자리를 기록합니다. 전체 생활이동 250개 지역을 해석하며, 서울 행정동은 자치구로 합칩니다. INCHEON_2026과 HWASEONG_2026은 SGIS 다각형 합집합과 MOIS 코드 합집합을 한 지역으로 결합합니다. 양쪽을 별도로 중복 가중하지 않습니다. 그룹 후 244개 지역·209,563개 TIMS 격자를 후보로 읽는 것을 확인했습니다. 대표점 기반 관계는 경계 전체의 일치를 입증하지 않으며, 개편 지역 내부 세분화는 모형화하지 않습니다.

- 생성 코드 SHA256: f1be4a1a79824add32f72523693c0f87615f5cf42fac738138448740e5fa1a5b
- 교차표 CSV SHA256: 9fa4f7d453b1e5253c218ae5e0c172caac7471edd8fc3cde623cb4579a910931
- 출력: experiments/.cache/kr-public/livingmove-sgis-crosswalk.csv
- 원본 경계: 국가데이터처 SGIS 2025Q2 (data.go.kr 15129688)
- 역지오코딩: https://dapi.kakao.com/v2/local/geo/coord2regioncode.json

코드와 출력은 OSRM MANIFEST.json inputs에 각각 해시를 남깁니다. 이후 실제 행렬·보정·C7 실행 결과는 EXECUTION_REPORT.md의 최신 항목을 기준으로 합니다.

## 한계

- 실제 합승 성과 미검증. 모의 일수·요청·정산 반복을 관측 학생 이용량으로 세지 않는다.
- 생활이동은 통신 기반 추정치이며 포천은 시군구 단위라 캠퍼스 단위가 아니다.
- 귀가 목적이 학생 하교를 대표한다는 가정. 2030 비율은 학생 비율도 귀가 연령 비율도 아니다.
- TIMS 잔차는 집계 평균이라 개별 오차를 과소추정한다. 비선형 평균·요금 지역 혼합·미터 clock 부재 편향이 있다.
- 캐시 행렬 약 51.5%가 관측 구간 합성이며 시간대 교통 변동을 재현하지 않는다. 기본 하루 100건·참여율 50/100/150%는 가정이다.
- C6의 비양수 일행-반복 제외와 C7의 양수 조건부 잔차 추출은 서로 다른 조건부 분모다. 노쇼 보전 전액 회수와 승계 수락을 가정한다.

재현 명령과 산출물 설명은 ../README.md, 실행 입력·생산 코드 해시는 ../reports/의 JSON을 따른다.


지역 전체 TIMS 분모는 tims_regions.py가 공식 SGIS 2025Q2 시군구 경계(data.go.kr 15129688)에 TIMS 500m 격자 중심점을 공간 결합해 만든다. EPSG:5179 UTM-K 중심점 계산식·공식 PRJ의 투영 파라미터·경계 밖 격자 수와 영업건수·원본 해시는 reports/tims-region-MANIFEST.json에 있다. 최근접 지점의 지역을 이식하지 않는다. SGIS 코드와 행정안전부 코드의 연결은 시도명+시군구명 유일 일치로 검증한다.

포천 사용점 4개는 모두 소흘읍이므로 포천 전체 시내 흐름을 대표하지 않는다. 공간 커버리지 보정 후에도 TIMS 밀도가 귀가 밀도를 대신한다는 가정과 격자 중심점의 경계 배정 한계가 남는다. 포천 내부 이동 제외 민감도를 기본 조건 옆에 보고한다. 이 조건은 100요청/일과 시간 분포를 고정하고 목적지만 재정규화한다. 출처: 국가데이터처(통계청) SGIS, 2025년 2분기 센서스용 행정구역 경계.

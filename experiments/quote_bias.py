"""C16 앱 견적(카카오 예상 요금 x (1+quote-bias))이 실제 요금보다 평균 몇 % 다른지 공개자료로 잇는다.

앱은 카카오 길찾기 예상 요금에 quote-bias(6.57%)를 곱해 견적을 낸다. 그런데 6.57%는 C5에서
'요금표 공식'과 TIMS 실제 평균 요금을 비교해 나온 값이고, 카카오 견적과 실제 요금을 비교한 적은 없다.
같은 운행의 카카오 견적과 실제 요금이 함께 공개된 자료는 없으므로, 요금표 공식을 다리로 쓴다.

  카카오 / 공식  : 이미 받아 둔 카카오 미래경로 견적 400건(kakao-calibration.csv) 중 대진대 출발 200건.
                   카카오가 준 거리·시간을 그대로 포천 낮 요금표 공식에 넣어 같은 운행끼리 비교한다.
  실제 / 공식    : TIMS 2025 통학권 격자·월 평균(C5와 같은 자료·공식)을 평균 운행거리대별로 나눈다.
  앱 견적 / 실제 = (1 + bias) x (카카오/공식) / (실제/공식), 거리대마다 따로 계산한다.

거리대 비중은 C12(지금 서비스, 대진대) 모의 요청의 혼자 거리 분포를 쓴다. 새 API 호출은 없다.
"""
import csv
import json
import random
import statistics
import sys
from pathlib import Path

import duckdb

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from fares import korea_tariff  # noqa: E402

CACHE = HERE / '.cache'
REPORTS = HERE / 'reports'
KAKAO = CACHE / 'osrm' / 'kakao-calibration.csv'
TIMS = CACHE / 'tims-monthly-safe.csv'
COMMUTE = HERE.parent.parent / 'taximate-matching-sim' / 'realdata' / 'data' / 'tims_commute_grids.csv'
C12_CASES = CACHE / 'c12' / 'cases.tsv'
BIAS = 0.0657  # C16 이전 앱 기본값(app.fare.quote-bias). 이 결과로 앱 기본값은 0이 됐다.
BIAS_NOW = 0.0  # 지금 앱 기본값: 카카오 견적 그대로
BANDS = ('0-5', '5-15', '15-30', '30+')
POCHEON = '41650'
SEED = 20261121
BOOT = 4000


def band(km):
    return '0-5' if km < 5 else '5-15' if km < 15 else '15-30' if km < 30 else '30+'


def chain(kakao_over_formula, actual_over_formula, bias=BIAS):
    """비율(1+r)끼리 이어 카카오/실제, 앱 견적/실제의 상대 차이를 돌려준다."""
    kakao_vs_actual = (1 + kakao_over_formula) / (1 + actual_over_formula) - 1
    return kakao_vs_actual, (1 + bias) * (1 + kakao_vs_actual) - 1


def quantile(values, q):
    s = sorted(values)
    if not s:
        return None
    pos = (len(s) - 1) * q
    lo, hi = int(pos), min(int(pos) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)


def kakao_rows(path=KAKAO):
    out = []
    with open(path, encoding='utf-8', newline='') as f:
        for x in csv.DictReader(f):
            if x['kind'] != 'hub' or not x['kakao_taxi'] or not x['kakao_km']:
                continue
            km, sec, fare = float(x['kakao_km']), float(x['kakao_seconds']), float(x['kakao_taxi'])
            formula = korea_tariff(km, sec / 60, int(x['hour']), 'pocheon')
            out.append(dict(pair=x['pair_id'], km=km, band=band(km), hour=int(x['hour']),
                            inside=x['destination_region'].startswith(POCHEON),
                            kakao=fare, formula=formula, ratio=fare / formula - 1))
    return out


def summarize(values, rng):
    if not values:
        return dict(n=0)
    boots = [statistics.median(rng.choices(values, k=len(values))) for _ in range(BOOT)]
    return dict(n=len(values), median=statistics.median(values), mean=statistics.mean(values),
                p25=quantile(values, .25), p75=quantile(values, .75),
                median_ci95=[quantile(boots, .025), quantile(boots, .975)])


def tims_by_band(tims=TIMS, commute=COMMUTE):
    db = duckdb.connect()
    db.execute("""CREATE MACRO krfare(km,mins) AS
      round(4800+100*(ceil(greatest(0,km-2.)*1000/83.-1e-10)+
        CASE WHEN km>2. THEN ceil(greatest(0,mins*60-km/15*3600)/20.-1e-10) ELSE 0 END))""")
    p = lambda path: "'" + str(Path(path).resolve()).replace('\\', '/').replace("'", "''") + "'"
    db.execute(f"CREATE TABLE c AS SELECT grid FROM read_csv_auto({p(commute)})")
    db.execute(f"""CREATE TABLE t AS SELECT *, distance_m/trips/1000 km, time_s/trips/60 mins,
      fare_won/trips actual FROM read_csv_auto({p(tims)})
      WHERE trips>0 AND fare_won>0 AND distance_m>0 AND time_s>0 AND grid IN (SELECT grid FROM c)""")
    rows = db.execute("""SELECT CASE WHEN km<5 THEN '0-5' WHEN km<15 THEN '5-15' WHEN km<30 THEN '15-30' ELSE '30+' END b,
      count(*), sum(trips), avg(km), median(actual/krfare(km,mins)-1), avg(actual/krfare(km,mins)-1),
      sum(fare_won)/sum(trips*krfare(km,mins))-1
      FROM t GROUP BY 1""").fetchall()
    out = {b: dict(grid_months=n, trips=int(trips), mean_km=km, median=med, mean=mean, trip_weighted=tw)
           for b, n, trips, km, med, mean, tw in rows}
    total = db.execute("SELECT count(*), avg(actual/krfare(km,mins)-1), sum(fare_won)/sum(trips*krfare(km,mins))-1 FROM t").fetchone()
    return out, dict(grid_months=total[0], mean=total[1], trip_weighted=total[2])


def service_weights(path=C12_CASES):
    counts = {b: 0 for b in BANDS}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('R\t'):
                counts[band(float(line.split('\t')[5]))] += 1
    n = sum(counts.values())
    return {b: counts[b] / n for b in BANDS}, n


def analyze():
    rng = random.Random(SEED)
    kakao = kakao_rows()
    tims, tims_total = tims_by_band()
    weights, requests = service_weights()
    per_band = {}
    for b in BANDS:
        values = [r['ratio'] for r in kakao if r['band'] == b]
        k = summarize(values, rng)
        t = tims.get(b, {})
        entry = dict(kakao_over_formula=k, actual_over_formula=t, service_share=weights[b])
        if k['n'] and t:
            # 실제/공식은 격자·월 중앙값과 운행 가중 평균 두 가지로 범위를 낸다.
            lo_t, hi_t = sorted([t['median'], t['trip_weighted']])
            kv_hi, app_hi = chain(k['median'], lo_t)
            kv_lo, app_lo = chain(k['median'], hi_t)
            entry.update(kakao_vs_actual=[kv_lo, kv_hi], app_vs_actual=[app_lo, app_hi])
        per_band[b] = entry
    usable = [b for b in BANDS if 'app_vs_actual' in per_band[b]]
    share = sum(weights[b] for b in usable)
    weighted = {key: [sum(weights[b] * per_band[b][key][i] for b in usable) / share for i in (0, 1)]
                for key in ('kakao_vs_actual', 'app_vs_actual')}
    # 거리대마다 카카오 표본 수가 달라(5~15km는 8건뿐) 표본 재추출로 가중 평균의 95% 구간을 낸다.
    # 실제/공식 두 값(격자·월 중앙값, 운행 가중)을 모두 넣어 한 구간으로 합친다.
    by_band = {b: [r['ratio'] for r in kakao if r['band'] == b] for b in usable}
    draws, draws_now = [], []
    for _ in range(BOOT):
        medians = {b: statistics.median(rng.choices(by_band[b], k=len(by_band[b]))) for b in usable}
        for variant in ('median', 'trip_weighted'):
            pairs = [(weights[b], chain(medians[b], tims[b][variant])) for b in usable]
            draws.append(sum(w * c[1] for w, c in pairs) / share)
            draws_now.append(sum(w * c[0] for w, c in pairs) / share)  # 보정 0 = 카카오 견적 그대로
    weighted['app_vs_actual_ci95'] = [quantile(draws, .025), quantile(draws, .975)]
    weighted['kakao_vs_actual_ci95'] = [quantile(draws_now, .025), quantile(draws_now, .975)]
    split = {name: summarize([r['ratio'] for r in kakao if r['inside'] == flag], rng)
             for name, flag in (('destination_in_pocheon', True), ('destination_outside_pocheon', False))}
    return dict(
        experiment='C16 quote bias bridge', seed=SEED, bootstrap=BOOT, bias_before=BIAS, bias_now=BIAS_NOW,
        inputs=dict(kakao=str(KAKAO), tims=str(TIMS), commute_grids=str(COMMUTE), c12_cases=str(C12_CASES)),
        kakao_hub_quotes=len(kakao), kakao_all=summarize([r['ratio'] for r in kakao], rng), kakao_split=split,
        tims_commute_total=tims_total, per_band=per_band, service_requests=requests,
        service_weighted=dict(bands=usable, share_covered=share, **weighted),
        limitations=[
            '카카오 견적과 실제 요금을 같은 운행으로 비교한 것이 아니다. 요금표 공식을 사이에 둔 간접 비교다.',
            'TIMS는 격자·월 평균이라 30km 넘는 운행은 평균 거리가 그 대에 드는 격자·월로만 대신한다(표본이 적다).',
            '공식에는 시계외·심야 외 할증, 통행료, 실제 저속 시간 계산이 없다. 카카오가 시계외 할증을 어떻게 넣는지 공개돼 있지 않다.',
            '카카오 견적은 2026-11-23 낮 시간 미래경로 예측이며 실제 미터 결제가 아니다.',
            'TIMS 통학권 격자에는 포천 밖(다른 요금 지역) 승차도 섞여 있다. C5와 같은 범위를 그대로 썼다.',
        ])


def pct(x, digits=1):
    return '—' if x is None else f'{x * 100:+.{digits}f}%'


def markdown(r):
    w = r['service_weighted']
    lines = ['# C16 앱 견적과 실제 요금의 차이 (카카오·요금표 공식·TIMS 연결)', '',
             f"앱 견적 = 카카오 예상 요금 × (1 + quote-bias). 이 실험 전 기본값은 {r['bias_before']:.2%}였다. 6.57%는 '요금표 공식 대비 실제'에서 나온 값이라, "
             '카카오 견적에 곱해도 되는지는 확인된 적이 없었다. 같은 운행의 카카오 견적과 실제 요금이 함께 공개된 자료가 없어 요금표 공식을 다리로 잇는다.', '',
             '## 결론', '',
             f"- 대진대 출발 카카오 견적 {r['kakao_hub_quotes']}건은 같은 거리·시간의 요금표 공식보다 중앙값 {pct(r['kakao_all']['median'])} 높다. "
             f"실제 평균 요금은 같은 공식보다 거리가 길수록 덜 높다(30km 넘으면 거의 같다).",
             f"- 지금 서비스 거리 분포로 가중하면 카카오 견적은 실제 평균보다 {pct(w['kakao_vs_actual'][0])} ~ {pct(w['kakao_vs_actual'][1])} 높다"
             f"(95% 구간 {pct(w['kakao_vs_actual_ci95'][0])} ~ {pct(w['kakao_vs_actual_ci95'][1])}). 예전 앱 코드의 전제(카카오 견적이 실제보다 낮다)와 반대다.",
             f"- 예전 앱 견적(×{1 + r['bias_before']:.4f})은 실제 평균보다 {pct(w['app_vs_actual'][0])} ~ {pct(w['app_vs_actual'][1])} 높았다"
             f"(95% 구간 {pct(w['app_vs_actual_ci95'][0])} ~ {pct(w['app_vs_actual_ci95'][1])}). 이용자가 실제보다 더 내고 차액이 적립금에 쌓이는 쪽이었다.",
             f"- **이 결과로 앱 보정을 {r['bias_now']:.0%}로 바꿨다**(카카오 견적 그대로). 간접 추정이라 깎지는(음수) 않았고, 이용자는 여전히 실제보다 조금 더 내는 쪽이다. "
             '확정 분담금 구조라 차액은 적립금이 맡고, 운영 중 실제 요금 입력이 쌓이면 다시 맞춘다.',
             '- 모의실험(C11~C15)의 견적은 카카오가 아니라 OSRM 경로 + 요금표 공식이고, 그 견적이 실제보다 6.57% 낮다는 것은 C5로 확인됐으므로 실험에는 6.57%를 그대로 둔다.', '',
             '## 거리대별', '',
             '| 거리대 | 지금 서비스 비중 | 카카오/공식 (건수, 중앙값, 95% CI) | 실제/공식 (TIMS 격자·월 수, 중앙값 · 운행가중) | 카카오/실제 = 지금 앱 견적/실제 | 예전 앱 견적(×1.0657)/실제 |',
             '|---|---:|---|---|---|---|']
    for b in BANDS:
        e = r['per_band'][b]; k = e['kakao_over_formula']; t = e['actual_over_formula']
        kk = f"{k['n']}건, {pct(k.get('median'))} [{pct(k['median_ci95'][0])}, {pct(k['median_ci95'][1])}]" if k['n'] else '0건'
        tt = f"{t['grid_months']:,}, {pct(t['median'])} · {pct(t['trip_weighted'])}" if t else '—'
        kv = f"{pct(e['kakao_vs_actual'][0])} ~ {pct(e['kakao_vs_actual'][1])}" if 'kakao_vs_actual' in e else '—'
        av = f"{pct(e['app_vs_actual'][0])} ~ {pct(e['app_vs_actual'][1])}" if 'app_vs_actual' in e else '—'
        lines.append(f"| {b}km | {e['service_share']:.0%} | {kk} | {tt} | {kv} | {av} |")
    s = r['kakao_split']
    lines += ['', '## 포천 안·밖 목적지', '',
              f"- 목적지가 포천 안: {s['destination_in_pocheon']['n']}건, 카카오/공식 중앙값 {pct(s['destination_in_pocheon']['median'])}",
              f"- 목적지가 포천 밖: {s['destination_outside_pocheon']['n']}건, 카카오/공식 중앙값 {pct(s['destination_outside_pocheon']['median'])}",
              '- 포천 밖으로 나가는 운행에서 차이가 크다. 카카오가 시계외 할증 등을 반영하는 방식이 공개돼 있지 않아 원인은 단정하지 않는다.', '',
              '## 읽는 법', '',
              '- 카카오/공식: 카카오가 준 거리·시간을 C5와 같은 포천 낮 요금표 공식에 넣은 값 대비 카카오 예상 요금.',
              '- 실제/공식: TIMS 2025 통학권 격자·월의 평균 거리·시간을 같은 공식에 넣은 값 대비 실제 평균 요금(C5와 같은 범위).',
              '- 카카오/실제 = (1+카카오/공식)/(1+실제/공식) − 1. 실제/공식은 격자·월 중앙값과 운행 가중 평균 두 값으로 범위를 냈다.',
              f"- 예전 앱 견적/실제 = (1+{r['bias_before']})×(1+카카오/실제) − 1. 지금 앱(보정 {r['bias_now']:.0%})은 카카오/실제와 같다. 일행 확정 금액에는 여기에 여유분 1%가 더 붙는다.",
              f"- 거리대 비중: C12 지금 서비스 모의 요청 {r['service_requests']:,}건의 혼자 거리.", '',
              '## 한계', ''] + [f'- {x}' for x in r['limitations']]
    lines += ['', '출처: 카카오모빌리티 길찾기 미래경로 API(C7 보정용으로 이미 받은 400건), 한국교통안전공단 TIMS 2025 택시운행정보 격자별 영업통계(data.go.kr 15165682), 포천시 택시요금표.']
    return '\n'.join(lines) + '\n'


def main():
    report = analyze()
    REPORTS.mkdir(exist_ok=True)
    (REPORTS / 'c16-quote-bias.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (REPORTS / 'c16-quote-bias.md').write_text(markdown(report), encoding='utf-8')
    print(markdown(report))


if __name__ == '__main__':
    main()

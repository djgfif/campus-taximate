"""C14 요금 보정의 위험: 견적이 실제 요금과 평균 x% 어긋나면 누가 얼마를 떠안나.

6.57%는 "요금표로 계산한 견적"과 TIMS 2025 실제 영업요금의 차이(C7)에서 나왔다. 예전 앱은 이를 카카오 길찾기 견적에 곱했다.
카카오 견적과 실제 요금을 같은 운행으로 비교할 공개 자료가 없으므로, 실제 요금이 견적보다 평균 x% 높다는 가정을 바꿔 가며 본다.
C16(시계외 할증 반영)에서 카카오 견적은 실제 평균과 거의 같다고 추정돼 앱 보정은 0이 됐다. 그 범위를 행으로 더한다.

방법: C12(대진대, 30일, 지금 서비스, 성별 반영)가 만든 일행마다 확정 금액 F와 실제 요금 M이 있다(오차 1배).
M은 평균 6.57% 높은 오차 분포에서 뽑혔으므로, x% 세계의 실제 요금은 M × (1 + x) / (1 + 0.0657)로 옮긴다.
일행마다 적립금 변화 = F − M_x. 이를 C12와 같은 한 학기 80일 × 2,000경로로 쌓아
- 적자 확률(초기 기금 0), 99%로 버티는 초기 기금, 학기 말 1% 최악 잔액(운영자 손실),
- 이용자가 실제 요금보다 더 낸 비율(F가 M_x보다 클 때, 확정 금액 기준)을 본다.
적립금은 이용자에게 돌려줄 잔액이 아니라 운영자가 차액을 떠안는 정산 계정으로 정의한다(이용자 돈 보관이 아니다).
"""
from __future__ import annotations
import csv, json, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import fixed_share_and_timing as c11
import run

HERE = Path(__file__).resolve().parent
REPORTS = HERE / 'reports'
ASSUMED = .0657
C16 = REPORTS / 'c16-quote-bias.json'


def c16_scenarios(path=C16):
    """C16의 '카카오 견적이 실제보다 k 높음' 범위를 이 표의 x로 바꾼다.

    x는 '보정 전 견적(=카카오) 대비 실제'이므로 x = 1/(1+k) − 1. 예전 앱은 확정 금액 그대로(보정 6.57% 포함),
    지금 앱은 보정 0이라 확정 금액에서 보정을 뺀다(quote_scale = 1/(1+0.0657)).
    """
    if not path.exists():
        return ()
    k = json.loads(path.read_text(encoding='utf-8'))['service_weighted']['kakao_vs_actual']
    return tuple(round(1 / (1 + v) - 1, 4) for v in sorted(k, reverse=True))


def c16_text(path=C16):
    if not path.exists():
        return 'C16 미생성'
    k = json.loads(path.read_text(encoding='utf-8'))['service_weighted']['kakao_vs_actual']
    return f'카카오가 실제보다 {100 * k[0]:+.1f}~{100 * k[1]:+.1f}%'


NOW = c16_scenarios()
SCENARIOS = (0., .03, ASSUMED, .10, .15)
APPS = (('now', '지금 앱(보정 0), C16 추정', 1 / (1 + ASSUMED)), ('before', '예전 앱(보정 6.57%), C16 추정', 1.))
LEVELS = (15, 35, 70, 100)


def rows(path=c12.CACHE / 'fixed.csv', spread=1.):
    with path.open(encoding='utf-8', newline='') as f:
        return [r for r in csv.DictReader(f) if abs(float(r['spread']) - spread) < 1e-9]


# 스트레스 시험(ChatGPT 검토 제안): 개별 오차를 TIMS 격자·월 잔차에서 뽑아 실제보다 좁을 수 있고,
# 같은 날 여러 운행이 함께 비싸지는 공통 충격(정체·날씨)이 없다. 지금 앱의 C16 최악 쪽에서
# 오차 폭 1/1.5/2배 × 하루 공통 충격(로그정규, 평균 1) 표준편차 0/5/10%를 학기 경로 20,000개로 본다.
STRESS_PATHS = 20000
STRESS_SPREADS = (1., 1.5, 2.)
STRESS_SHOCKS = (0., .05, .10)
STRESS_LEVELS = (35, 100)


def stress(rs, n, x, quote_scale, shock_sd, paths, rng):
    """경로·날마다 공통 충격 s를 그날 모든 실제 요금에 곱한다. 1.5배 초과 관리자 검토 규칙은 충격 뒤 요금으로 다시 판단한다."""
    scale = (1 + x) / (1 + ASSUMED)
    days = []
    for day in range(c12.DAYS):
        rr = sorted([r for r in rs if int(r['n']) == n and int(r['day']) == day], key=lambda r: (int(r['finish']), int(r['group_id'])))
        days.append((np.array([int(r['fixed_quote']) * quote_scale for r in rr], dtype=float),
                     np.array([int(r['meter']) * scale for r in rr], dtype=float)))
    P, T = paths.shape
    shock = np.exp(shock_sd * rng.standard_normal((P, T)) - shock_sd ** 2 / 2) if shock_sd else np.ones((P, T))
    totals = np.zeros((P, T)); minima = np.zeros((P, T))
    for d, (f, m) in enumerate(days):
        if not len(f):
            continue
        idx = np.nonzero(paths == d)
        meter = shock[idx][:, None] * m[None, :]
        delta = np.where(meter > 1.5 * f[None, :], 0., f[None, :] - meter)
        cum = np.cumsum(delta, axis=1)
        totals[idx] = cum[:, -1]
        minima[idx] = np.minimum(0., cum.min(axis=1))
    ending = np.cumsum(totals, axis=1)
    previous = np.concatenate((np.zeros((P, 1)), ending[:, :-1]), axis=1)
    minimum = np.minimum(0., np.min(previous + minima, axis=1))
    required = -minimum
    groups = sum(len(f) for f, _ in days)
    review = sum(float(np.mean(shock[np.nonzero(paths == d)][:, None] * m[None, :] > 1.5 * f[None, :])) * len(f)
                 for d, (f, m) in enumerate(days) if len(f) and np.any(paths == d)) / groups if groups else None
    return dict(paths=P, review_share=review, probability_ever_negative=float(np.mean(minimum < 0)),
                seed99=float(c11.seed_fund(np.ceil(required), .99)), end_mean=float(ending[:, -1].mean()), end_p1=float(np.quantile(ending[:, -1], .01)))


def scenario(rs, n, x, paths, quote_scale=1.):
    """하루 요청 n건, 실제 요금이 (보정 전) 견적보다 평균 x 높은 세계.

    quote_scale: 확정 금액에 곱하는 배수. C12 확정 금액에는 보정 6.57%가 들어 있으므로
    보정 0인 지금 앱은 1/(1+0.0657)을 곱해 보정을 뺀다.
    """
    scale = (1 + x) / (1 + ASSUMED)
    totals, minima, over = [], [], []
    paid = meters = 0.; review = 0
    for day in range(c12.DAYS):
        rr = sorted([r for r in rs if int(r['n']) == n and int(r['day']) == day], key=lambda r: (int(r['finish']), int(r['group_id'])))
        deltas = []
        for r in rr:
            fixed, meter = int(r['fixed_quote']) * quote_scale, int(r['meter']) * scale
            # 운영과 같은 규칙(C11Finance.fixed): 실제 요금이 확정 금액의 1.5배를 넘으면 관리자 검토로 가고 적립금에서 빼지 않는다.
            exceptional = meter > 1.5 * fixed
            review += exceptional
            deltas.append(0 if exceptional else round(fixed - meter))
            over.append(fixed / meter - 1); paid += fixed; meters += meter
        traj = c11.reserve_recursion(deltas)
        totals.append(int(traj[-1])); minima.append(int(traj.min()))
    stats, _ = c11.path_statistics(totals, minima, paths)
    stats.pop('trajectory', None)
    o = np.asarray(over)
    # 이용자 전체가 낸 돈 ÷ 실제 요금 전체 − 1. 일행별 비율의 평균은 오차 분포가 한쪽으로 긴 탓에 부풀려진다.
    return dict(groups=len(o), review_share=review / len(o) if len(o) else None, mean_paid_over_meter=paid / meters - 1 if meters else None,
                share_paid_more_than_meter=float((o > 0).mean()) if len(o) else None, **stats)


def main():
    rs = rows()
    paths = np.asarray(json.loads((c12.CACHE / 'semester-paths.json').read_text(encoding='utf-8')))
    out = {str(n): {f'{x:.4f}': scenario(rs, n, x, paths) for x in SCENARIOS} for n in LEVELS}
    for n in LEVELS:
        for key, _, qs in APPS:
            for x in NOW:
                out[str(n)][f'{key}_{x:.4f}'] = scenario(rs, n, x, paths, quote_scale=qs)
    stress_out = {}
    if NOW:
        worst = max(NOW)  # 실제가 카카오 견적에 가장 가까운(운영자에게 불리한) 쪽
        big = np.random.default_rng(c12.SEED + 16).integers(0, c12.DAYS, size=(STRESS_PATHS, paths.shape[1]))
        by_spread = {s: rows(spread=s) for s in STRESS_SPREADS}
        for n in STRESS_LEVELS:
            base2k = stress(by_spread[1.], n, worst, APPS[0][2], 0., paths, np.random.default_rng(1))
            cells = {}
            for s in STRESS_SPREADS:
                for sd in STRESS_SHOCKS:
                    cells[f'{s}_{sd}'] = stress(by_spread[s], n, worst, APPS[0][2], sd, big, np.random.default_rng(c12.SEED + int(100 * s) + int(1000 * sd)))
            stress_out[str(n)] = dict(x=worst, paths_2000=base2k, cells=cells)
    run.json_write(REPORTS / 'c14-fare-risk.json', dict(assumed=ASSUMED, scenarios=SCENARIOS, c16_x=NOW, levels=out, stress=stress_out))
    won = lambda v: f'{v:,.0f}원'
    L = ['# C14 요금 보정의 위험', '',
         '견적 보정 6.57%는 요금표 견적과 TIMS 2025 실제 영업요금의 차이에서 나왔고, 예전 앱은 이를 카카오 길찾기 견적에 곱했다. '
         '카카오 견적과 실제 요금을 같은 운행으로 비교할 공개 자료가 없어, 실제 요금이 견적보다 평균 몇 % 높은지를 바꿔 가며 적립금과 이용자 부담을 본다.',
         f"- C16에서 시계외 할증까지 반영하니 카카오 견적은 실제 평균과 거의 같아({c16_text()}) **앱 보정은 0(카카오 견적 그대로)이 됐다**. "
         '각 표 아래쪽의 C16 추정 행이 그 범위다: 실제가 카카오 견적보다 낮은 x에서, 지금 앱(확정 금액에서 6.57% 보정을 뺌)과 예전 앱(보정 포함)을 나란히 본다.', '',
         '- 자료: C12 대진대 30일(지금 서비스, 성별 반영)의 일행별 확정 금액과 실제 요금(오차 1배)을 x에 맞게 옮김. 한 학기 80일 × 2,000경로.',
         '- 적립금은 이용자에게 돌려줄 잔액이 아니라 운영자가 차액을 떠안는 정산 계정이다. 이용자는 어느 경우에도 확정 몫보다 더 내지 않는다.', '']
    for n in LEVELS:
        L += [f'## 하루 {n}건', '', '| 실제가 견적보다 평균 | 일행 수 | 이용자 전체가 실제 요금보다 더 낸 비율 | 더 낸 일행 | 1.5배 초과(관리자 검토) | 학기 중 적자 확률(기금 0) | 99% 초기 기금 | 학기 말 1% 최악 잔액 | 학기 말 평균 |',
              '|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        row = lambda label, s: (f"| {label} | {s['groups']:,} | {100 * s['mean_paid_over_meter']:+.1f}% | "
                                f"{100 * s['share_paid_more_than_meter']:.0f}% | {100 * s['review_share']:.1f}% | {100 * s['probability_ever_negative']:.0f}% | {won(s['seed99'])} | {won(s['end_p1'])} | {won(s['end_mean'])} |")
        for x in SCENARIOS:
            L.append(row(f"{100 * x:+.2f}%{' (예전 앱 가정)' if x == ASSUMED else ''}", out[str(n)][f'{x:.4f}']))
        for key, label, _ in APPS:
            for x in NOW:
                L.append(row(f"{100 * x:+.2f}% {label}", out[str(n)][f'{key}_{x:.4f}']))
        L.append('')
    a = out['35']; lo, hi = a[f'{0.:.4f}'], a[f'{.15:.4f}']
    L += ['## 요약', '',
          f"- 하루 35건 기준, 카카오 견적이 이미 정확하다면(실제가 평균 0% 높음) 이용자 전체는 실제 요금보다 {100 * lo['mean_paid_over_meter']:+.1f}% 더 내고 적립금은 학기 말 평균 {won(lo['end_mean'])} 남는다. "
          '보정이 과하면 이용자가 손해를 보는 쪽이고, 그 돈은 적립금에 쌓인다.',
          f"- 실제가 평균 15% 높다면 이용자는 그대로 확정 몫만 내고, 운영자가 학기 말 1% 최악 {won(hi['end_p1'])}를 떠안는다(99% 초기 기금 {won(hi['seed99'])}).",
          f"- 예전 앱 가정(6.57%)에서도 일행의 {100 * a[f'{ASSUMED:.4f}']['share_paid_more_than_meter']:.0f}%는 실제 요금보다 조금 더 내고, 요금이 크게 더 나온 소수 일행의 차액을 적립금이 메운다. 확정 분담금은 '평소 조금 더 내는 대신 크게 더 나와도 더 내지 않는' 보험이다."]
    if NOW:
        now = [a[f'now_{x:.4f}'] for x in NOW]; before = [a[f'before_{x:.4f}'] for x in NOW]
        span = lambda ss, k: f"{100 * min(s[k] for s in ss):+.1f}% ~ {100 * max(s[k] for s in ss):+.1f}%"
        L.append(f"- C16 범위에서 하루 35건 기준, 이용자 전체가 실제 요금보다 더 내는 비율은 예전 앱 {span(before, 'mean_paid_over_meter')} → 지금 앱 {span(now, 'mean_paid_over_meter')}. "
                 f"지금 앱은 기금 없이 시작하면 학기 중 한 번이라도 적자가 날 확률이 최대 {100 * max(s['probability_ever_negative'] for s in now):.0f}%지만, "
                 f"초기 기금 {won(max(s['seed99'] for s in now))}이면 99% 버틴다. 이용자 초과 부담은 거의 없어지고, 운영자 쪽 위험은 작은 초기 기금으로 감당할 수준이다.")
    L.append('- 이 보정들은 같은 운행 비교로 확인된 값이 아니다. 운영 중 실제 요금 입력이 쌓이면 보정값을 다시 맞춰야 하고(정산마다 실제 요금이 기록된다), 그 전까지는 위 표가 위험의 범위다.')
    for n, s in stress_out.items():
        b = s['paths_2000']; c0 = s['cells']['1.0_0.0']
        L += ['', f'## 스트레스 시험: 지금 앱, 하루 {n}건', '',
              f"C16 범위 중 운영자에게 가장 불리한 쪽(실제가 카카오 견적보다 {100 * s['x']:+.2f}%)에서, 개별 오차를 TIMS 격자·월 잔차보다 넓게(1.5·2배) 하고 "
              '같은 날 모든 운행에 함께 곱하는 공통 충격(정체·날씨 같은 날 단위 변동, 로그정규 평균 1)을 더한다. 학기 경로는 20,000개.', '',
              f"- 경로 수 안정성(오차 1배, 충격 0): 2,000경로 99% 기금 {won(b['seed99'])}·적자 확률 {100 * b['probability_ever_negative']:.0f}% → "
              f"20,000경로 {won(c0['seed99'])}·{100 * c0['probability_ever_negative']:.0f}%.", '',
              '| 개별 오차 폭 | 공통 충격 0% | 공통 충격 5% | 공통 충격 10% |', '|---|---|---|---|']
        for sp in STRESS_SPREADS:
            cells = [s['cells'][f'{sp}_{sd}'] for sd in STRESS_SHOCKS]
            L.append(f"| {sp:g}배 | " + ' | '.join(f"99% 기금 {won(c['seed99'])}, 적자 확률 {100 * c['probability_ever_negative']:.0f}%, 관리자 검토 {100 * c['review_share']:.1f}%" for c in cells) + ' |')
        worst_cell = max(s['cells'].values(), key=lambda c: c['seed99'])
        L += ['', f"- 기본 가정의 99% 초기 기금 {won(c0['seed99'])}은 확정 운영비가 아니라 범위의 한 점이다. 이 표의 가장 나쁜 조합에서는 {won(worst_cell['seed99'])}이 필요하다.",
              '- 하루 공통 충격이 개별 오차 폭보다 기금을 크게 바꾼다. 같은 날 모든 일행이 함께 비싸지면 서로 상쇄되지 않기 때문이다.',
              '- 개별 오차 폭을 키우면 실제 요금이 확정 금액의 1.5배를 넘어 관리자 검토로 빠지는 일행(적립금에서 빼지 않음)이 늘어, 충격이 클 때는 기금이 오히려 덜 늘 수 있다.',
              '- 오차 폭과 공통 충격의 크기는 관측값이 아니라 가정이다(개별 일반택시 요금은 공개되지 않는다).']
    (REPORTS / 'c14-fare-risk.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    print('\n'.join(L[-4:]))


if __name__ == '__main__':
    main()

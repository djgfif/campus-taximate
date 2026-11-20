"""C14 요금 보정의 위험: 견적 보정 6.57%가 카카오 견적에 맞지 않으면 누가 얼마를 떠안나.

6.57%는 "요금표로 계산한 견적"과 TIMS 2025 실제 영업요금의 차이(C7)에서 나왔다. 서비스는 이를 카카오 길찾기 견적에 곱한다.
카카오 견적과 실제 요금을 같은 운행으로 비교할 공개 자료가 없으므로, 실제 요금이 견적보다 평균 x% 높다는 가정을 바꿔 가며 본다.

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
SCENARIOS = (0., .03, ASSUMED, .10, .15)
LEVELS = (15, 35, 70, 100)


def rows(path=c12.CACHE / 'fixed.csv'):
    with path.open(encoding='utf-8', newline='') as f:
        return [r for r in csv.DictReader(f) if abs(float(r['spread']) - 1.) < 1e-9]


def scenario(rs, n, x, paths):
    """하루 요청 n건, 실제 요금이 견적보다 평균 x 높은 세계."""
    scale = (1 + x) / (1 + ASSUMED)
    totals, minima, over = [], [], []
    paid = meters = 0.; review = 0
    for day in range(c12.DAYS):
        rr = sorted([r for r in rs if int(r['n']) == n and int(r['day']) == day], key=lambda r: (int(r['finish']), int(r['group_id'])))
        deltas = []
        for r in rr:
            fixed, meter = int(r['fixed_quote']), int(r['meter']) * scale
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
    run.json_write(REPORTS / 'c14-fare-risk.json', dict(assumed=ASSUMED, scenarios=SCENARIOS, levels=out))
    won = lambda v: f'{v:,.0f}원'
    L = ['# C14 요금 보정의 위험', '',
         '견적 보정 6.57%는 요금표 견적과 TIMS 2025 실제 영업요금의 차이에서 나왔고, 서비스는 이를 카카오 길찾기 견적에 곱한다. '
         '카카오 견적과 실제 요금을 같은 운행으로 비교할 공개 자료가 없어, 실제 요금이 견적보다 평균 몇 % 높은지를 바꿔 가며 적립금과 이용자 부담을 본다.', '',
         '- 자료: C12 대진대 30일(지금 서비스, 성별 반영)의 일행별 확정 금액과 실제 요금(오차 1배)을 x에 맞게 옮김. 한 학기 80일 × 2,000경로.',
         '- 적립금은 이용자에게 돌려줄 잔액이 아니라 운영자가 차액을 떠안는 정산 계정이다. 이용자는 어느 경우에도 확정 몫보다 더 내지 않는다.', '']
    for n in LEVELS:
        L += [f'## 하루 {n}건', '', '| 실제가 견적보다 평균 | 일행 수 | 이용자 전체가 실제 요금보다 더 낸 비율 | 더 낸 일행 | 1.5배 초과(관리자 검토) | 학기 중 적자 확률(기금 0) | 99% 초기 기금 | 학기 말 1% 최악 잔액 | 학기 말 평균 |',
              '|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        for x in SCENARIOS:
            s = out[str(n)][f'{x:.4f}']
            L.append(f"| {100 * x:+.2f}%{' (지금 가정)' if x == ASSUMED else ''} | {s['groups']:,} | {100 * s['mean_paid_over_meter']:+.1f}% | "
                     f"{100 * s['share_paid_more_than_meter']:.0f}% | {100 * s['review_share']:.1f}% | {100 * s['probability_ever_negative']:.0f}% | {won(s['seed99'])} | {won(s['end_p1'])} | {won(s['end_mean'])} |")
        L.append('')
    a = out['35']; lo, hi = a[f'{0.:.4f}'], a[f'{.15:.4f}']
    L += ['## 요약', '',
          f"- 하루 35건 기준, 카카오 견적이 이미 정확하다면(실제가 평균 0% 높음) 이용자 전체는 실제 요금보다 {100 * lo['mean_paid_over_meter']:+.1f}% 더 내고 적립금은 학기 말 평균 {won(lo['end_mean'])} 남는다. "
          '보정이 과하면 이용자가 손해를 보는 쪽이고, 그 돈은 적립금에 쌓인다.',
          f"- 실제가 평균 15% 높다면 이용자는 그대로 확정 몫만 내고, 운영자가 학기 말 1% 최악 {won(hi['end_p1'])}를 떠안는다(99% 초기 기금 {won(hi['seed99'])}).",
          f"- 지금 가정에서도 일행의 {100 * a[f'{ASSUMED:.4f}']['share_paid_more_than_meter']:.0f}%는 실제 요금보다 조금 더 내고, 요금이 크게 더 나온 소수 일행의 차액을 적립금이 메운다. 확정 분담금은 '평소 조금 더 내는 대신 크게 더 나와도 더 내지 않는' 보험이다.",
          '- 그래서 6.57%는 근거가 확인된 값이 아니라 운영 시작 때의 가정이다. 운영 중 실제 요금 입력이 쌓이면 보정값을 다시 맞춰야 하고(정산마다 실제 요금이 기록된다), 그 전까지는 위 표가 위험의 범위다.']
    (REPORTS / 'c14-fare-risk.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    print('\n'.join(L[-4:]))


if __name__ == '__main__':
    main()

import { describe, expect, it } from 'vitest'
import { nextActionOf, netShareOfHistory, POSTPAID_TRIP_STAGES, stageOfParty, stagesForParty, TRIP_STAGES, toTripViewModel } from './tripViewModel.js'

describe('tripViewModel', () => {
  it('16개 내부 상태를 6단계 사용자 진행 상태로 투영한다', () => {
    expect(stageOfParty({ status: 'CONFIRMED' })).toBe('CONFIRMED')
    expect(stageOfParty({ status: 'PREPAYING' })).toBe('PAYMENT')
    expect(stageOfParty({ status: 'READY' })).toBe('PREPARATION')
    expect(stageOfParty({ status: 'READY', calledAt: '2026-11-25T10:00:00Z' })).toBe('BOARDING')
    expect(stageOfParty({ status: 'READY', startedAt: '2026-11-25T10:05:00Z' })).toBe('RIDE')
    expect(stageOfParty({ status: 'OBJECTION_WINDOW' })).toBe('SETTLEMENT')
    expect(stageOfParty({ status: 'SETTLED' })).toBe('DONE')
    expect(stageOfParty({ status: 'DISSOLVED' })).toBe('CANCELED')
  })

  it('역할과 서버 권한 플래그에 따라 다음 할 일을 반환한다', () => {
    const vm = toTripViewModel({
      id: 10,
      status: 'AWAITING_FARE',
      payer: true,
      canSettle: true,
      adminReviewRequired: false,
      unreadChat: 2,
    }, 1)
    expect(vm.role).toBe('PAYER')
    expect(vm.stage).toBe('SETTLEMENT')
    expect(vm.nextAction.kind).toBe('SUBMIT_FARE')
    expect(vm.unreadChat).toBe(2)
  })

  it('결제자의 이용 내역 실부담금은 택시 전액이 아니라 본인 최종 몫으로 계산한다', () => {
    const item = { paid: 10757, partyPaid: 10757, soloFare: 10657 }
    const party = {
      payer: true,
      myShare: 4355,
      settlement: { shares: [{ payer: true, amount: 4355 }, { payer: false, amount: 6402 }] },
    }
    expect(netShareOfHistory(item, party)).toBe(4355)
  })

  it('후불 정산 v3 일행은 결제 단계 없는 6단계를, v0·v2 일행은 기존 7단계를 쓴다', () => {
    expect(stagesForParty({ status: 'CONFIRMED' })).toBe(TRIP_STAGES)
    expect(stagesForParty({ status: 'PREPAYING', paymentFlowVersion: 2 })).toBe(TRIP_STAGES)
    expect(TRIP_STAGES).toHaveLength(7)
    const v3 = stagesForParty({ status: 'READY', paymentFlowVersion: 3 })
    expect(v3).toBe(POSTPAID_TRIP_STAGES)
    expect(v3.map((s) => s.id)).toEqual(['CONFIRMED', 'PREPARATION', 'BOARDING', 'RIDE', 'SETTLEMENT', 'DONE'])
    expect(v3.find((s) => s.id === 'SETTLEMENT').label).toBe('정산·결제')
    for (const status of ['AWAITING_FARE', 'OBJECTION_WINDOW', 'REVIEW_PENDING', 'FARE_MISSING']) {
      expect(stageOfParty({ status, paymentFlowVersion: 3 })).toBe('SETTLEMENT')
    }
    expect(stageOfParty({ status: 'SETTLED', paymentFlowVersion: 3 })).toBe('DONE')
    expect(stageOfParty({ status: 'COMPLETED', paymentFlowVersion: 3 })).toBe('DONE')
  })

  it('후불 정산 v3 동승자에게는 확인하고 결제를, 결제자에게는 수납 현황 확인을 안내한다', () => {
    const settlement = { myConfirmed: false, payable: true, autoFinalizeAt: '2026-11-25T16:00:00Z', objectionDeadlineAt: '2026-11-25T15:00:00Z' }
    const rider = { id: 3, status: 'OBJECTION_WINDOW', paymentFlowVersion: 3, payer: false, settlement }
    expect(nextActionOf(rider, 1)).toEqual({ kind: 'CONFIRM_AND_PAY', label: '정산 금액을 확인하고 결제해 주세요', deadlineAt: '2026-11-25T16:00:00Z' })
    expect(nextActionOf({ ...rider, settlement: { ...settlement, autoFinalizeAt: null } }, 1).deadlineAt).toBe('2026-11-25T15:00:00Z')
    expect(nextActionOf({ ...rider, settlement: { ...settlement, myConfirmed: true } }, 1).kind).toBe('VIEW_SETTLEMENT')
    expect(nextActionOf({ ...rider, settlement: { ...settlement, payable: false } }, 1).kind).toBe('VIEW_SETTLEMENT')
    const payer = nextActionOf({ ...rider, payer: true }, 1)
    expect(payer.kind).toBe('VIEW_SETTLEMENT')
    expect(payer.label).toBe('동승자 확인·결제 현황을 확인해 주세요')
  })

  it('v2 선결제 일행의 다음 할 일은 그대로 선결제다', () => {
    const party = { id: 4, status: 'PREPAYING', paymentFlowVersion: 2, payer: false, myPrepaid: false, prepayDeadlineAt: '2026-11-25T10:00:00Z' }
    expect(nextActionOf(party, 1)).toEqual({ kind: 'PREPAY', label: '분담금 결제를 마쳐 주세요', deadlineAt: '2026-11-25T10:00:00Z' })
    expect(nextActionOf({ ...party, myPrepaid: true }, 1).kind).toBe('WAIT_PREPAY')
  })
})

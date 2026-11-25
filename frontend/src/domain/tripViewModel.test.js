import { describe, expect, it } from 'vitest'
import { netShareOfHistory, stageOfParty, toTripViewModel } from './tripViewModel.js'

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
})

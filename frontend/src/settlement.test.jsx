import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FinancialDetails, LegacySettlement, PayerInput, SettlementScreen } from './SettleScreens.jsx'
import { PartyScreen } from './PartyScreens.jsx'
import { api } from './api.js'
import History from './History.jsx'

vi.mock('./MapView.jsx', () => ({ default: () => null }))
const now = '2026-11-11T00:00:00Z'
const members = [{ requestId: 101, memberId: 1, nickname: '가', pointName: '역전', rideOrder: 1 }, { requestId: 102, memberId: 2, nickname: '나', pointName: '시청', rideOrder: 2 }, { requestId: 103, memberId: 3, nickname: '다', pointName: '버스터미널', rideOrder: 3 }]
const party = { id: 11, status: 'OBJECTION_WINDOW', direction: 'FROM_HUB', members, myRequestId: 101, myShare: 3200, payer: false, payerId: 3, payerNickname: '다', anchorName: '정문', meetingPoint: '정문 앞', expectedTotal: 16800, departureAt: '2026-11-10T00:00:00Z', prepayDeadlineAt: '2026-11-09T23:40:00Z', freeCancelUntil: '2026-11-09T23:40:00Z', settlement: { paidAmount: 16800, canCorrect: false, objectionDeadlineAt: '2026-11-12T00:00:00Z', finalizedAt: null, shares: [{ memberId: 1, amount: 3200 }, { memberId: 2, amount: 5600 }, { memberId: 3, amount: 8000, payer: true }], payments: [], receiptAvailable: true } }
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: true, messages: [] })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('새 정산 화면', () => {
  it('정산 확정 뒤 환불·추가 결제·앱 정산 기록상 받을 돈(실제 송금 없음)을 서버 기록대로 표시한다', () => {
    const payments = [
      { orderId: 'p1', purpose: 'PREPAY', amount: 3680, approvedAmount: 3680, refundedAmount: 480, refundRequestedAmount: 480, status: 'APPROVED' },
      { orderId: 'a1', purpose: 'ADDITIONAL', amount: 892, approvedAmount: 0, refundedAmount: 0, status: 'REQUIRED', deadlineAt: '2026-11-12T00:00:00Z', simulated: true },
    ]
    render(<SettlementScreen party={{ ...party, payer: true, status: 'SETTLED', settlement: { ...party.settlement, finalizedAt: now, payments, mockPayoutAmount: 8800 } }} myId={3} now={now} config={{ demo: true }} />)
    expect(screen.getByRole('heading', { name: '정산 확정' })).toBeTruthy()
    expect(within(screen.getByRole('region', { name: '선결제 기록' })).getByText('480원')).toBeTruthy()
    expect(screen.getByText('환불은 카드 반영까지 3~4영업일 정도 걸려요.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '추가 결제 892원' })).toBeTruthy()
    expect(screen.getByText('앱 정산 기록상 받을 돈(실제 송금 없음) 8,800원')).toBeTruthy()
    expect(screen.getByText('실제 송금 없음')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^(보냄|받음)$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: '요금 이의', exact: true })).toBeNull()
  })

  it('이의 기간에는 사유를 서버로 보내고 결제를 실행하지 않는다', async () => {
    const object = vi.spyOn(api, 'objectToFare').mockResolvedValue()
    const confirm = vi.spyOn(api, 'confirmPayment')
    const create = vi.spyOn(api, 'paymentOrder')
    const updated = vi.fn().mockResolvedValue()
    render(<SettlementScreen party={party} myId={1} now={now} config={{ demo: true }} onUpdated={updated} />)
    expect(screen.getByText('요금 이의 기간 24시간 남음')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '요금 이의', exact: true }))
    const dialog = screen.getByRole('dialog', { name: '요금 이의' })
    fireEvent.change(within(dialog).getByLabelText('이의 사유'), { target: { value: '영수증 금액이 달라요' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '이의 보내기' }))
    await waitFor(() => expect(object).toHaveBeenCalledWith(11, '영수증 금액이 달라요'))
    await waitFor(() => expect(updated).toHaveBeenCalledOnce())
    expect(confirm).not.toHaveBeenCalled()
    expect(create).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: '추가 결제' })).toBeNull()
  })

  it('관리자 검토 중에는 금액 수정 버튼을 제공하지 않는다', () => {
    render(<SettlementScreen party={{ ...party, status: 'REVIEW_PENDING' }} myId={3} now={now} onCorrect={() => {}} />)
    expect(screen.getByRole('heading', { name: '관리자 검토 중' })).toBeTruthy()
    expect(screen.getByText('요금 이의가 있어 관리자 검토 중이에요. 환불과 추가 결제, 받을 돈의 정산을 보류해요.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '금액 고치기' })).toBeNull()
  })

  it('실제 금액 입력에는 계좌 칸이 없고 15% 초과 차이를 경고한다', async () => {
    vi.spyOn(api, 'settlementPreview').mockResolvedValue(party.settlement.shares)
    render(<PayerInput party={{ ...party, payer: true, status: 'AWAITING_FARE', settlement: null }} now={now} />)
    fireEvent.change(screen.getByLabelText('실제 결제 금액'), { target: { value: '20000' } })
    expect(screen.getByText('예상 요금보다 19% 높아요')).toBeTruthy()
    expect(screen.queryByLabelText('은행')).toBeNull()
    expect(screen.queryByLabelText('받을 계좌')).toBeNull()
    expect(screen.getByLabelText('영수증 사진')).toBeTruthy()
    await waitFor(() => expect(api.settlementPreview).toHaveBeenCalledWith(11, 20000))
  })

  it('이전 송금 데이터는 계좌와 보냄·받음 버튼 없이 읽기만 한다', () => {
    const sent = vi.spyOn(api, 'sent')
    const received = vi.spyOn(api, 'received')
    render(<LegacySettlement party={{ id: 12, status: 'COMPLETED', settlement: { paidAmount: 10000, bankName: '은행', accountNumber: '123456789', transfers: [{ senderNickname: '가', amount: 3000, status: 'RECEIVED' }] } }} />)
    expect(screen.getByText('이전 송금 방식의 기록은 읽기만 할 수 있어요.')).toBeTruthy()
    expect(screen.getByText('3,000원 · 받음')).toBeTruthy()
    expect(screen.queryByText('123456789')).toBeNull()
    expect(screen.queryByRole('button', { name: /^(보냄|받음)$/ })).toBeNull()
    expect(sent).not.toHaveBeenCalled(); expect(received).not.toHaveBeenCalled()
  })

  it('확정 전에는 앱 정산 기록상 받을 돈(실제 송금 없음)을 실제 지급으로 표현하지 않는다', () => {
    render(<FinancialDetails party={party} />)
    expect(screen.queryByText(/앱 정산 기록상 받을 돈(실제 송금 없음)/)).toBeNull()
    expect(screen.getAllByText('3,200원')).toHaveLength(2)
  })
})

describe('일행 상태', () => {
  it('무료 취소 마감과 전액 환불 안내를 보이고 모르는 상태에도 안전하다', async () => {
    const waiting = { ...party, settlement: null, payer: true, status: 'READY', departureAt: '2026-11-11T01:00:00Z', freeCancelUntil: '2026-11-11T00:30:00Z', canCancel: true }
    const view = render(<PartyScreen party={waiting} now={now} myId={3} />)
    expect(screen.getByText('무료 취소 마감')).toBeTruthy()
    expect(screen.getByText('무료 취소 마감 전 취소하면 승인된 선결제를 전액 환불해요.')).toBeTruthy()
    expect(screen.getByText('하차 순서')).toBeTruthy()
    view.rerender(<PartyScreen party={{ ...waiting, status: 'FUTURE_STATUS' }} now={now} myId={3} />)
    expect(screen.getByText('일행 상태 확인 중')).toBeTruthy()
    expect(screen.queryByText('FUTURE_STATUS')).toBeNull()
    await screen.findByText('일행 채팅은 읽기만 가능해요.')
  })
})

describe('이용 내역의 결제 기록', () => {
  it('선택한 일행 상세에서 환불과 앱 정산 기록상 받을 돈(실제 송금 없음) 기록을 읽는다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([{ id: 101, stage: 'SETTLED', direction: 'FROM_HUB', anchorName: '정문', pointName: '역전', windowStart: now, partyId: 11, partyIds: [11], soloFare: 6000, paid: 3200 }])
    vi.spyOn(api, 'fareAccuracy').mockResolvedValue({ settled: 1, within: 1 })
    const detail = vi.spyOn(api, 'party').mockResolvedValue({ ...party, payer: true, status: 'COMPLETED', settlement: { ...party.settlement, finalizedAt: now, mockPayoutAmount: 8800, payments: [{ orderId: 'history-prepay', purpose: 'PREPAY', status: 'APPROVED', amount: 3680, approvedAmount: 3680, refundedAmount: 480, refundRequestedAmount: 480 }] } })
    render(<History onBack={() => {}} />)
    await screen.findByText('앱 정산 기록상 받을 돈(실제 송금 없음) 8,800원')
    expect(detail).toHaveBeenCalledWith(11)
    const records = screen.getByRole('region', { name: '결제·환불 기록' })
    expect(within(records).getByText('480원')).toBeTruthy()
    expect(within(records).getByText('실제 송금 없음')).toBeTruthy()
    expect(screen.getByText('정산 완료')).toBeTruthy()
  })
})

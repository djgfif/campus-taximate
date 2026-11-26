import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import SettlementPage from './SettlementPage.jsx'
import { AdminScreen } from '../ReviewScreens.jsx'
import { FairnessNote } from '../ui.jsx'
import { api, ApiError } from '../api.js'

const now = '2026-11-25T10:00:00Z'

const members = [
  { requestId: 101, memberId: 1, nickname: '가학생', pointName: '역전', rideOrder: 1 },
  { requestId: 102, memberId: 2, nickname: '나학생', pointName: '시청', rideOrder: 2 },
  { requestId: 103, memberId: 3, nickname: '다학생', pointName: '터미널', rideOrder: 3 },
]

const postpaidOrder = {
  orderId: 'postpaid-55-1',
  purpose: 'POSTPAID',
  amount: 3200,
  status: 'REQUIRED',
  approvedAmount: 0,
  refundedAmount: 0,
  refundRequestedAmount: 0,
  deadlineAt: '2026-11-26T10:00:00Z',
  simulated: true,
  settlementRevision: 2,
}

const settlement = {
  paidAmount: 16800,
  revision: 2,
  canCorrect: false,
  objectionDeadlineAt: '2026-11-25T16:00:00Z',
  autoFinalizeAt: '2026-11-25T16:00:00Z',
  finalizedAt: null,
  shares: [
    { memberId: 1, amount: 3200 },
    { memberId: 2, amount: 5600 },
    { memberId: 3, amount: 8000, payer: true },
  ],
  payments: [postpaidOrder],
  receiptAvailable: true,
  myConfirmed: false,
  payable: true,
  confirmedCount: 0,
  totalRidersToConfirm: 2,
}

const party = {
  id: 55,
  paymentFlowVersion: 3,
  status: 'OBJECTION_WINDOW',
  direction: 'FROM_HUB',
  members,
  myRequestId: 101,
  myShare: 3200,
  payer: false,
  payerId: 3,
  payerNickname: '다학생',
  anchorName: '대진대 정문',
  meetingPoint: '정문 앞',
  expectedTotal: 16800,
  departureAt: '2026-11-25T09:00:00Z',
  prepayDeadlineAt: null,
  freeCancelUntil: '2026-11-25T08:40:00Z',
  unreadChat: 0,
  settlement,
}

const withSettlement = (patch, partyPatch = {}) => ({ ...party, ...partyPatch, settlement: { ...settlement, ...patch } })

function renderPage(target, onUpdated = vi.fn().mockResolvedValue()) {
  render(
    <SettlementPage party={target} myId={1} now={now} config={{ demo: true, simulatedPayment: true }}
      onUpdated={onUpdated} onHome={vi.fn()} onDetail={vi.fn()} onMessages={vi.fn()} />,
  )
  return onUpdated
}

const payButton = () => screen.getByRole('button', { name: '3,200원 확인하고 결제' })

beforeEach(() => {
  window.matchMedia = () => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} })
  vi.spyOn(api, 'partyPeople').mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('후불 정산 v3 확인하고 결제', () => {
  it('정산 금액을 확인하고 결제하면 정산 차수·주문·금액을 보내고 최신 정산을 다시 불러온다', async () => {
    const confirm = vi.spyOn(api, 'confirmAndPay').mockResolvedValue({
      order: { ...postpaidOrder, status: 'APPROVED', approvedAmount: 3200 }, partyStatus: 'OBJECTION_WINDOW', revision: 2, processing: false, settled: false,
    })
    const legacyConfirm = vi.spyOn(api, 'confirmPayment')
    const onUpdated = renderPage(party)

    const card = screen.getByRole('region', { name: '정산 확인과 결제' })
    expect(within(card).getByText('6시간 자동 확정 마감')).toBeTruthy()
    expect(screen.getByText(/이의가 없으면 6시간 뒤 자동으로 정산이 확정돼요\./)).toBeTruthy()
    expect(screen.queryByText(/정산 적립금/)).toBeNull()
    expect(screen.getByRole('button', { name: '요금 이의' })).toBeTruthy()

    fireEvent.click(payButton())

    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
    const [partyId, body, key] = confirm.mock.calls[0]
    expect(partyId).toBe(55)
    expect(body).toEqual({ revision: 2, orderId: 'postpaid-55-1', amount: 3200 })
    expect(typeof key).toBe('string')
    expect(key.length).toBeLessThanOrEqual(100)
    await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1))
    expect(legacyConfirm).not.toHaveBeenCalled()
  })

  it('정산 금액이 수정됐다는 409 응답이면 알리고 최신 금액을 다시 불러온다', async () => {
    vi.spyOn(api, 'confirmAndPay').mockRejectedValue(new ApiError(409, 'SETTLEMENT_REVISED', '정산 금액이 바뀌었어요.'))
    const onUpdated = renderPage(party)

    fireEvent.click(payButton())

    expect(await screen.findByText('정산 금액이 수정됐어요. 최신 금액을 다시 불러왔어요.')).toBeTruthy()
    await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('다른 409 응답은 서버 안내를 그대로 보여 주고 정산을 다시 부르지 않는다', async () => {
    vi.spyOn(api, 'confirmAndPay').mockRejectedValue(new ApiError(409, 'SETTLEMENT_LOCKED', '다른 결제를 처리하고 있어요.'))
    const onUpdated = renderPage(party)

    fireEvent.click(payButton())

    expect((await screen.findByRole('alert')).textContent).toBe('다른 결제를 처리하고 있어요.')
    expect(onUpdated).not.toHaveBeenCalled()
  })

  it('승인 확인 중(202) 응답이면 차분한 안내를 띄우고 정산을 다시 불러온다', async () => {
    vi.spyOn(api, 'confirmAndPay').mockResolvedValue({
      order: { ...postpaidOrder, status: 'PROCESSING' }, partyStatus: 'OBJECTION_WINDOW', revision: 2, processing: true, settled: false,
    })
    const onUpdated = renderPage(party)

    fireEvent.click(payButton())

    expect(await screen.findByText('결제 승인을 확인하고 있어요. 잠시 후 자동으로 반영돼요.')).toBeTruthy()
    await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1))
  })

  it('관리자 검토나 탑승 조사로 잠긴 정산은 결제 버튼을 막고 이유를 알린다', async () => {
    const confirm = vi.spyOn(api, 'confirmAndPay')
    renderPage(withSettlement({ payable: false }))

    expect(screen.getByText('관리자 검토나 탑승 조사가 끝난 뒤 결제할 수 있어요.')).toBeTruthy()
    const button = payButton()
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    await waitFor(() => expect(confirm).not.toHaveBeenCalled())
  })

  it('이미 확인하고 결제했으면 요금 이의 버튼 없이 완료 상태를 보여 준다', () => {
    renderPage(withSettlement({ myConfirmed: true, payments: [{ ...postpaidOrder, status: 'APPROVED', approvedAmount: 3200 }] }))

    expect(screen.getByText('정산 확인과 결제를 마쳤어요')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '요금 이의' })).toBeNull()
    expect(screen.queryByRole('button', { name: /확인하고 결제/ })).toBeNull()
  })

  it('승인 확인 중인 주문이면 다시 결제하지 않도록 상태만 보여 준다', () => {
    renderPage(withSettlement({ payments: [{ ...postpaidOrder, status: 'PROCESSING' }] }))

    expect(screen.getByText('결제 승인을 확인하고 있어요')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '요금 이의' })).toBeNull()
    expect(screen.queryByRole('button', { name: /확인하고 결제/ })).toBeNull()
  })

  it('결제자에게는 예정 수령액·수령 확정액·미수납액과 확인·결제 인원을 보여 준다', () => {
    renderPage(withSettlement({ expectedPayoutAmount: 8800, collectedPayoutAmount: 3200, unpaidAmount: 5600, confirmedCount: 1, totalRidersToConfirm: 2, payments: [] },
      { payer: true, myRequestId: 103 }))

    const progress = screen.getByRole('region', { name: '동승자 확인·결제 현황' })
    expect(within(progress).getByRole('heading', { name: '1 / 2명 확인·결제 완료' })).toBeTruthy()
    expect(within(progress).getByText('예정 수령액')).toBeTruthy()
    expect(within(progress).getByText('8,800원')).toBeTruthy()
    expect(within(progress).getByText('수령 확정액')).toBeTruthy()
    expect(within(progress).getByText('3,200원')).toBeTruthy()
    expect(within(progress).getByText('미수납액')).toBeTruthy()
    expect(within(progress).getByText('5,600원')).toBeTruthy()
    expect(within(progress).getByText('1 / 2명')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /확인하고 결제/ })).toBeNull()
    expect(screen.queryByText(/앱 정산 기록상 받을 돈/)).toBeNull()
  })

  it('정산 확정 전이라도 보전 분담금 주문이 있으면 결제 카드를 보여 준다', () => {
    const compensation = { orderId: 'comp-55-2', purpose: 'COMPENSATION', amount: 4000, status: 'REQUIRED', approvedAmount: 0, refundedAmount: 0, deadlineAt: '2026-11-26T10:00:00Z', simulated: true }
    renderPage(withSettlement({ payments: [postpaidOrder, compensation] }))

    expect(screen.getByRole('heading', { name: '보전 분담금 결제 4,000원' })).toBeTruthy()
    expect(screen.getByRole('region', { name: '보전 분담금 결제 기록' })).toBeTruthy()
    expect(screen.getByRole('region', { name: '후불 분담금 결제 기록' })).toBeTruthy()
  })
})

describe('후불 정산 v3 관리자 화면', () => {
  const overdue = [
    { id: 1, partyId: 55, memberId: 1, amount: 3200, deadlineAt: '2026-11-26T10:00:00Z', purpose: 'POSTPAID' },
    { id: 2, partyId: 56, memberId: 2, amount: 4000, deadlineAt: '2026-11-26T10:00:00Z', purpose: 'COMPENSATION' },
    { id: 3, partyId: 57, memberId: 4, amount: 900, deadlineAt: '2026-11-26T10:00:00Z', purpose: 'ADDITIONAL', adminHandledAt: '2026-11-26T12:00:00Z', adminNote: '연락 완료' },
  ]

  it('v3에서는 적립금 탭을 감추고 미정산 항목에 결제 목적을 표시한다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    vi.spyOn(api, 'adminOverdue').mockResolvedValue(overdue)
    const reserve = vi.spyOn(api, 'adminReserve')
    render(<AdminScreen allowed config={{ paymentFlowVersion: 3 }} onBack={vi.fn()} />)

    expect(screen.queryByRole('button', { name: '적립금' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '미정산' }))

    expect(await screen.findByText('후불 분담금')).toBeTruthy()
    expect(screen.getByText('이탈 보전금')).toBeTruthy()
    expect(screen.getByText('추가 정산금')).toBeTruthy()
    expect(screen.getByText('확인 완료')).toBeTruthy()
    expect(reserve).not.toHaveBeenCalled()
  })

  it('v2 설정이면 적립금 탭을 그대로 보여 준다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    render(<AdminScreen allowed config={{ paymentFlowVersion: 2 }} onBack={vi.fn()} />)

    expect(screen.getByRole('button', { name: '적립금' })).toBeTruthy()
    await screen.findByText('처리할 항목이 없어요.')
  })

  it('결제자 정산 화면은 수납 현황을 중복 표시하지 않고 FairnessNote는 후불 실요금 기준을 안내한다', () => {
    renderPage(withSettlement({ expectedPayoutAmount: 8800, collectedPayoutAmount: 3200, unpaidAmount: 5600, confirmedCount: 1, totalRidersToConfirm: 2, payments: [] },
      { payer: true, myRequestId: 103 }))

    expect(screen.getAllByText('예정 수령액')).toHaveLength(1)
    expect(screen.queryByText('동승자 결제 수납')).toBeNull()

    render(<FairnessNote delay={5} solo={10000} share={5000} postpaid />)
    expect(screen.getByText(/하차 뒤 실제 요금을 각자 혼자 탈 때 요금 비율대로 나눠요/)).toBeTruthy()
    expect(screen.queryByText(/실제 요금이 더 나와도 더 내지 않아요/)).toBeNull()
  })

  it('후불 안내는 절약률을 설정값으로 보이고 실제 절약을 보장하지 않는다', () => {
    render(<FairnessNote delay={5} solo={10000} share={5000} rules={{ minSavingRate: 0.2 }} postpaid />)
    expect(screen.getByText(/예상 요금 기준 20% 이상 아낄 때만 일행이 되지만, 실제 요금에 따라 내는 돈은 달라질 수 있어요/)).toBeTruthy()
    expect(screen.getByText('80% 이하일 때만 일행이 돼요')).toBeTruthy()
    expect(screen.queryByText(/최소 10% 이상 절약돼요/)).toBeNull()
  })

  it('관리자 검색은 키 이름이 아니라 값으로만 거른다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    vi.spyOn(api, 'adminOverdue').mockResolvedValue(overdue)
    render(<AdminScreen allowed config={{ paymentFlowVersion: 3 }} onBack={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '미정산' }))
    await screen.findByText('후불 분담금')
    const search = screen.getByRole('searchbox', { name: '목록 검색' })

    fireEvent.change(search, { target: { value: 'partyId' } })
    expect(screen.getByText('검색 조건에 맞는 항목이 없어요.')).toBeTruthy()

    fireEvent.change(search, { target: { value: '연락 완료' } })
    expect(screen.getByRole('heading', { name: '일행 57 · 회원 4' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: '일행 55 · 회원 1' })).toBeNull()
  })
})

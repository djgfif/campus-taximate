import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import History from './History.jsx'
import { api } from './api.js'

const now = '2026-11-28T03:30:00Z'

function setWide(matches) {
  window.matchMedia = () => ({ matches, addEventListener() {}, removeEventListener() {} })
}

beforeEach(() => setWide(false))
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('History 이용 내역 UX 개선', () => {
  it('진행 중 건을 지난 이용보다 우선 배치해 먼저 선택하고 일행·메시지 바로가기를 제공한다', async () => {
    setWide(true)
    const onOpenParty = vi.fn()
    const onOpenMessages = vi.fn()
    vi.spyOn(api, 'history').mockResolvedValue([
      {
        id: 202, stage: 'SETTLED', direction: 'FROM_HUB', anchorName: '학교 정문', pointName: '서울역',
        windowStart: '2026-11-28T05:25:00Z', partyId: 2, partySize: 3, soloFare: 11100,
        paid: 14100, partyPaid: 14100, myShare: 5796, payer: true,
      },
      {
        id: 201, stage: 'OBJECTION_WINDOW', direction: 'TO_SCHOOL', anchorName: '학교 정문', pointName: '강남역',
        windowStart: '2026-11-28T03:15:00Z', partyId: 1, partySize: 2, soloFare: 9000,
        paid: 10600, partyPaid: 10600, myShare: 5272, payer: true,
      },
    ])
    vi.spyOn(api, 'party').mockResolvedValue({
      id: 1, paymentFlowVersion: 3, payer: true, myRequestId: 1, status: 'OBJECTION_WINDOW',
      members: [
        { memberId: 1, requestId: 1, nickname: '준호' },
        { memberId: 2, requestId: 2, nickname: '하은' },
      ],
      settlement: {
        paidAmount: 10600, expectedPayoutAmount: 5328, collectedPayoutAmount: 0, unpaidAmount: 5328,
        confirmedCount: 0, totalRidersToConfirm: 1,
        shares: [{ memberId: 1, payer: true, amount: 5272 }, { memberId: 2, payer: false, amount: 5328 }],
      },
    })

    render(<History onHome={vi.fn()} onOpenParty={onOpenParty} onOpenMessages={onOpenMessages} />)
    const detail = await screen.findByRole('complementary', { name: '선택한 이용 상세' })
    expect(within(detail).getByText('강남역 → 학교 정문')).toBeTruthy()
    expect(within(detail).getByText('5,272원')).toBeTruthy()
    expect(within(detail).getByText('실제 택시비 (현장 결제)')).toBeTruthy()
    expect(within(detail).getByText('10,600원')).toBeTruthy()

    fireEvent.click(within(detail).getByRole('button', { name: '일행·정산 화면 열기' }))
    fireEvent.click(within(detail).getByRole('button', { name: '메시지 열기' }))
    expect(onOpenParty).toHaveBeenCalledWith(1)
    expect(onOpenMessages).toHaveBeenCalledWith(1)
  })

  it('결제자의 누적 절약액과 본인 분담금을 정확히 표시하고 승인된 단일 영수증은 기본 접힘으로 중복을 줄인다', async () => {
    setWide(true)
    vi.spyOn(api, 'history').mockResolvedValue([
      {
        id: 301, stage: 'SETTLED', direction: 'FROM_HUB', anchorName: '학교 정문', pointName: '의정부역',
        windowStart: now, partyId: 4, partySize: 3, soloFare: 11100,
        paid: 14100, partyPaid: 14100, myShare: 5796, payer: true,
      },
    ])
    vi.spyOn(api, 'party').mockResolvedValue({
      id: 4, paymentFlowVersion: 3, payer: true, myRequestId: 10, status: 'COMPLETED',
      members: [
        { memberId: 10, requestId: 10, nickname: '준호' },
        { memberId: 11, requestId: 11, nickname: '현우' },
      ],
      settlement: {
        paidAmount: 14100, finalizedAt: now, expectedPayoutAmount: 8304, collectedPayoutAmount: 8304, unpaidAmount: 0,
        confirmedCount: 1, totalRidersToConfirm: 1,
        shares: [{ memberId: 10, payer: true, amount: 5796 }, { memberId: 11, payer: false, amount: 8304 }],
        payments: [{ orderId: 'p-4', purpose: 'POSTPAID', status: 'APPROVED', amount: 8304, approvedAmount: 8304, refundedAmount: 0 }],
      },
    })

    render(<History onHome={vi.fn()} />)
    await screen.findByText('같이 타서 아낀 돈')
    expect(screen.getByText('5,304원')).toBeTruthy()
    const detail = await screen.findByRole('complementary', { name: '선택한 이용 상세' })
    expect(within(detail).queryByText('주문 금액')).toBeNull()
    fireEvent.click(within(detail).getByRole('button', { name: '영수증 보기' }))
    expect(within(detail).getByText('주문 금액')).toBeTruthy()
  })

  it('필터 탭으로 진행 중/지난 이용을 전환하고 후불 소명 빈 주문에서는 선결제 보류 문구를 노출하지 않는다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([
      {
        id: 401, stage: 'BOARDING_UNCONFIRMED', endReason: 'BOARDING_UNCONFIRMED', direction: 'TO_SCHOOL',
        anchorName: '학교 정문', pointName: '포천시청', windowStart: now, partyId: null, soloFare: 10800,
      },
      {
        id: 402, stage: 'SETTLED', direction: 'FROM_HUB',
        anchorName: '학교 정문', pointName: '역전', windowStart: now, partyId: 7, soloFare: 9600, paid: 5014, myShare: 5014, payer: false,
      },
    ])
    vi.spyOn(api, 'ownPaymentHistory').mockResolvedValue([])

    render(<History onHome={vi.fn()} />)
    await screen.findByText('후불 정산 방식이거나 결제된 주문이 없어요.')
    expect(screen.queryByText(/선결제는 조사 중 보류돼요/)).toBeNull()

    fireEvent.click(screen.getByRole('tab', { name: /지난 이용/ }))
    await waitFor(() => expect(screen.queryByText('포천시청 → 학교 정문')).toBeNull())
    expect(screen.getByText('학교 정문 → 역전')).toBeTruthy()
  })

  it('정산 전 모이는 중 항목에서는 결제자라도 확정 분담금 대신 정산 전을 표시한다', async () => {
    setWide(true)
    vi.spyOn(api, 'history').mockResolvedValue([
      {
        id: 501, stage: 'READY', direction: 'FROM_HUB', anchorName: '학교 정문', pointName: '시장 입구',
        windowStart: now, partyId: 3, partySize: 3, soloFare: 12500, paid: null, partyPaid: null, myShare: null, payer: true,
      },
    ])
    vi.spyOn(api, 'party').mockResolvedValue({
      id: 3, paymentFlowVersion: 3, payer: true, myRequestId: 1, myShare: 6141, status: 'READY',
      members: [{ memberId: 1, requestId: 1, nickname: '준호' }],
      settlement: null,
    })

    render(<History onHome={vi.fn()} />)
    const detail = await screen.findByRole('complementary', { name: '선택한 이용 상세' })
    expect(within(detail).getByText('정산 전')).toBeTruthy()
    expect(within(detail).queryByText('6,141원')).toBeNull()
  })
})

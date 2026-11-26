import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import TripDetailPage from './TripDetailPage.jsx'
import MessagesPage from './MessagesPage.jsx'
import { api } from '../api.js'

const now = '2026-11-25T09:00:00Z'

const sampleParty = {
  id: 42,
  direction: 'FROM_HUB',
  status: 'READY',
  departureAt: '2026-11-25T09:30:00Z',
  freeCancelUntil: '2026-11-25T09:15:00Z',
  prepayDeadlineAt: '2026-11-25T09:10:00Z',
  payer: false,
  payerId: 2,
  payerNickname: '결제자',
  myRequestId: 101,
  myShare: 6200,
  myConfirmedShare: 6200,
  mySoloFare: 11400,
  canArrive: true,
  unreadChat: 3,
  groupVersion: 1,
  pickupPointName: '대진대 정문',
  members: [
    { requestId: 101, memberId: 1, nickname: '나학생', rideOrder: 1, pointName: '포천시청', expectedShare: 6200, arrived: false },
    { requestId: 102, memberId: 2, nickname: '결제자', rideOrder: 2, pointName: '의정부역', expectedShare: 5200, arrived: true },
  ],
}

beforeEach(() => {
  window.matchMedia = () => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} })
  vi.spyOn(api, 'partyPeople').mockResolvedValue([])
  vi.spyOn(api, 'partyMessages').mockResolvedValue({ messages: [], hasMore: false, writable: true, members: [] })
  vi.spyOn(api, 'readPartyMessages').mockResolvedValue({})
  vi.spyOn(api, 'partyBoardingCases').mockResolvedValue([])
  vi.spyOn(api, 'boardingState').mockResolvedValue({
    partyId: 42,
    codeAvailable: false,
    myBoardingVerified: false,
    members: [],
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('PR 3 이용 상세와 메시지함 분리', () => {
  it('이용 상세 화면은 채팅 입력창을 포함하지 않고 일행 메시지 열기 링크를 제공한다', async () => {
    const onMessages = vi.fn()
    render(
      <TripDetailPage
        party={sampleParty}
        now={now}
        myId={1}
        config={{ demo: false, rules: { maxDelayMinutes: 15, minSavingRate: 0.1 } }}
        run={vi.fn()}
        onUpdated={vi.fn()}
        onHome={vi.fn()}
        onDetail={vi.fn()}
        onMessages={onMessages}
      />,
    )

    expect(screen.getByText('같이 타는 사람')).toBeTruthy()
    expect(screen.getByText('금액 구성')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: '일행에게 보낼 메시지' })).toBeNull()

    const msgBtn = screen.getByRole('button', { name: /일행 메시지 열기/ })
    fireEvent.click(msgBtn)
    expect(onMessages).toHaveBeenCalledWith(42)
  })

  it('독립 메시지함은 대화 목록과 순수 채팅을 표시하고 이용 상세로 이동할 수 있다', async () => {
    const onOpenTrip = vi.fn()
    const onSelectParty = vi.fn()
    render(
      <MessagesPage
        state={{ now, parties: [sampleParty] }}
        activePartyId={42}
        onSelectParty={onSelectParty}
        onOpenTrip={onOpenTrip}
        onHome={vi.fn()}
      />,
    )

    expect(await screen.findByRole('textbox', { name: '일행에게 보낼 메시지' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /이용 상세/ }))
    expect(onOpenTrip).toHaveBeenCalledWith(42)
  })

  it('참여 중인 일행이 없으면 메시지함 빈 상태와 동승 찾기 버튼을 보여 준다', () => {
    const onHome = vi.fn()
    render(
      <MessagesPage
        state={{ now, parties: [] }}
        activePartyId={null}
        onSelectParty={vi.fn()}
        onOpenTrip={vi.fn()}
        onHome={onHome}
      />,
    )

    expect(screen.getByText('참여 중인 일행 대화가 없어요')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '동승 찾기' }))
    expect(onHome).toHaveBeenCalledTimes(1)
  })
})

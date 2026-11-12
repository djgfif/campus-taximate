import React from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import { PartyScreen } from './PartyScreens.jsx'

const now = '2026-11-12T09:00:00Z'
const base = {
  id: 7, direction: 'FROM_HUB', status: 'PREPAYING', departureAt: '2026-11-12T09:45:00Z', prepayDeadlineAt: '2026-11-12T09:25:00Z',
  freeCancelUntil: '2026-11-12T09:25:00Z', meetingPoint: '정문 앞', anchorName: '학교 정문', payer: false, payerId: 3, payerNickname: '강',
  myRequestId: 11, myShare: 3334, mySoloFare: 8300, canArrive: true, canCancel: true, unreadChat: 2,
  members: [
    { requestId: 11, memberId: 1, nickname: '산', rideOrder: 1, pointName: '시장 입구', expectedShare: 3334, arrived: false },
    { requestId: 12, memberId: 3, nickname: '강', rideOrder: 2, pointName: '2번째 하차', expectedShare: 3776, arrived: false },
  ],
}

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, messages: [] })
  vi.spyOn(api, 'readPartyMessages').mockResolvedValue({ lastReadId: 0 })
})
afterEach(() => vi.restoreAllMocks())

describe('일행 화면 구성', () => {
  it('진행 단계에서 지금 단계를 표시하고, 선결제 전에는 도착 버튼을 고정하지 않는다', () => {
    const view = render(<PartyScreen party={base} now={now} myId={1} run={vi.fn()} />)
    const steps = screen.getByRole('list', { name: '진행 단계' })
    expect(within(steps).getByText('선결제').getAttribute('aria-current')).toBe('step')
    const next = screen.getByRole('region', { name: '지금 할 일' })
    expect(within(next).getByText('선결제를 마쳐 주세요')).toBeTruthy()
    expect(view.container.querySelectorAll('.foot button')).toHaveLength(0)
  })

  it('결제를 마치면 만날 곳과 시간을 보여 주고 도착 버튼 하나만 고정한다', () => {
    const view = render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(within(screen.getByRole('region', { name: '지금 할 일' })).getByText(/학교 정문 · 정문 앞에서 만나요/)).toBeTruthy()
    const foot = view.container.querySelectorAll('.foot button')
    expect(foot).toHaveLength(1)
    expect(foot[0].textContent).toBe('도착했어요')
  })

  it('탭으로 진행·채팅·정보를 나누고 채팅 탭에 안 읽은 수를 보여 준다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    const chatTab = screen.getByRole('tab', { name: /채팅/ })
    expect(within(chatTab).getByLabelText('안 읽은 메시지 2개')).toBeTruthy()
    expect(screen.getByRole('tabpanel', { name: '진행' })).toBeTruthy()
    fireEvent.click(chatTab)
    expect(screen.getByRole('tab', { name: /채팅/ }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel', { name: /채팅/ })).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: '정보' }))
    expect(screen.getByText('규칙 자세히')).toBeTruthy()
    expect(screen.getByRole('button', { name: '내 탑승 취소' })).toBeTruthy()
  })

  it('채팅 탭은 대화방만 보여 주고 하단 고정 버튼을 숨긴다', () => {
    const view = render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(view.container.querySelectorAll('.foot button')).toHaveLength(1)
    fireEvent.click(screen.getByRole('tab', { name: /채팅/ }))
    expect(view.container.querySelectorAll('.foot button')).toHaveLength(0)
    expect(view.container.querySelector('.party-chat.full')).toBeTruthy()
  })

  it('모이는 곳 이름은 머리·지금 할 일·정보 탭에서 똑같다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    fireEvent.click(screen.getByRole('tab', { name: '정보' }))
    expect(screen.getAllByText('학교 정문 · 정문 앞', { exact: true }).length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByText('학교 정문', { exact: true })).toBeNull()
  })

  it('일행이 바뀌면 지금 할 일 아래에 새 몫과 변경 이탈을 바로 보여 준다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY', canLeaveAfterChange: true, changeExitFree: true, groupVersion: 2 }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.getByText('일행이 바뀌었어요')).toBeTruthy()
    expect(screen.getByRole('button', { name: '변경돼서 빠지기' })).toBeTruthy()
  })
})

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
    // 혼자 탈 때와 비교한 아끼는 돈은 '자세히' 창 맨 위에 있다(8,300 - 3,334).
    fireEvent.click(screen.getByRole('button', { name: '일행 정보 열기' }))
    const saving = screen.getByRole('region', { name: '아끼는 돈' })
    expect(within(saving).getByText('4,966원')).toBeTruthy()
  })

  it('내 선결제가 승인되면 다른 일행이 결제 중이어도 제목을 바꾼다', async () => {
    vi.spyOn(api, 'paymentOrder').mockResolvedValue({ orderId: 'p1', amount: 3834, purpose: 'PREPAY', status: 'APPROVED', deadlineAt: '2026-11-12T09:25:00Z', approvedAmount: 3834, refundedAmount: 0, simulated: true })
    render(<PartyScreen party={base} now={now} myId={1} run={vi.fn()} />)
    const next = screen.getByRole('region', { name: '지금 할 일' })
    expect(await within(next).findByText('선결제를 마쳤어요')).toBeTruthy()
    expect(within(next).getByText(/모두 결제하면 만날 시간과 장소를 알려 드려요/)).toBeTruthy()
    expect(within(next).queryByText('선결제를 마쳐 주세요')).toBeNull()
  })

  it('결제를 마치면 만날 곳과 시간을 보여 주고 도착 버튼 하나만 고정한다', () => {
    const view = render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(within(screen.getByRole('region', { name: '지금 할 일' })).getByText(/정문 앞 \(학교 정문\)에서 만나요/)).toBeTruthy()
    const foot = view.container.querySelectorAll('.foot button')
    expect(foot).toHaveLength(1)
    expect(foot[0].textContent).toBe('도착했어요')
  })

  it('휴대폰 일행 화면은 채팅방이다: 위 고정 카드, 그 아래 채팅, 자세히는 따로 연다', () => {
    const view = render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    const pinned = screen.getByRole('region', { name: '지금 상태' })
    expect(within(pinned).getByRole('region', { name: '지금 할 일' })).toBeTruthy()
    expect(view.container.querySelector('.room .party-chat.full')).toBeTruthy()
    expect(screen.queryByText('규칙 자세히')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '일행 정보 열기' }))
    const details = screen.getByRole('dialog', { name: '일행 정보' })
    expect(within(details).getByText('규칙 자세히')).toBeTruthy()
    expect(within(details).getByRole('button', { name: '내 탑승 취소' })).toBeTruthy()
    fireEvent.click(within(details).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog', { name: '일행 정보' })).toBeNull()
  })

  it('고정 카드는 접고 펼 수 있다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '접기' }))
    expect(screen.queryByRole('region', { name: '지금 할 일' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '펼치기' }))
    expect(screen.getByRole('region', { name: '지금 할 일' })).toBeTruthy()
  })

  it('채팅 + 메뉴에서 사진 보내기와 일행 정보를 연다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    return screen.findByRole('button', { name: '더 보기' }).then((more) => {
      fireEvent.click(more)
      const menu = screen.getByRole('menu', { name: '일행 도구' })
      expect(within(menu).getByRole('menuitem', { name: /사진 보내기/ })).toBeTruthy()
      fireEvent.click(within(menu).getByRole('menuitem', { name: '일행 정보·규칙' }))
      expect(screen.getByRole('dialog', { name: '일행 정보' })).toBeTruthy()
    })
  })

  it('넓은 화면에서는 채팅을 오른쪽 열에 늘 열어 두고 일행 정보도 왼쪽에 바로 보인다', () => {
    window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} })
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.queryByRole('button', { name: '일행 정보 열기' })).toBeNull()
    expect(screen.getByRole('complementary', { name: '일행 채팅' }).querySelector('.party-chat.full')).toBeTruthy()
    expect(screen.getByText('규칙 자세히')).toBeTruthy()
  })

  it('모이는 곳 이름은 머리·지금 할 일·일행 정보에서 똑같다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '일행 정보 열기' }))
    expect(screen.getAllByText('정문 앞 (학교 정문)', { exact: true }).length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByText('학교 정문', { exact: true })).toBeNull()
  })

  it('일행이 바뀌면 지금 할 일 아래에 새 몫과 변경 이탈을 바로 보여 준다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY', canLeaveAfterChange: true, changeExitFree: true, groupVersion: 2 }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.getByText('일행이 바뀌었어요')).toBeTruthy()
    expect(screen.getByRole('button', { name: '변경돼서 빠지기' })).toBeTruthy()
  })
})

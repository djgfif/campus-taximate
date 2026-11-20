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
    // 진행 탭 첫 화면에 혼자 탈 때와 비교한 아끼는 돈을 보인다(8,300 - 3,334).
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

  it('한 화면에 진행과 일행 정보를 잇고, 채팅은 위쪽 버튼에 안 읽은 수를 보여 준다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(screen.getByRole('region', { name: '진행' })).toBeTruthy()
    expect(screen.getByRole('region', { name: '일행 정보' })).toBeTruthy()
    expect(screen.getByText('규칙 자세히')).toBeTruthy()
    expect(screen.getByRole('button', { name: '내 탑승 취소' })).toBeTruthy()
    const open = screen.getByRole('button', { name: /일행 채팅 열기/ })
    expect(within(open).getByLabelText('안 읽은 메시지 2개')).toBeTruthy()
  })

  it('휴대폰에서 채팅은 화면 전체 창으로 열고 닫는다', () => {
    const view = render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(view.container.querySelector('.party-chat')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /일행 채팅 열기/ }))
    const sheet = screen.getByRole('dialog', { name: '일행 채팅' })
    expect(sheet.querySelector('.party-chat.full')).toBeTruthy()
    fireEvent.click(within(sheet).getByRole('button', { name: '채팅 닫기' }))
    expect(screen.queryByRole('dialog', { name: '일행 채팅' })).toBeNull()
  })

  it('넓은 화면에서는 채팅을 오른쪽 열에 늘 열어 둔다', () => {
    window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} })
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.queryByRole('button', { name: /일행 채팅 열기/ })).toBeNull()
    expect(screen.getByRole('complementary', { name: '일행 채팅' }).querySelector('.party-chat.full')).toBeTruthy()
  })

  it('모이는 곳 이름은 머리·지금 할 일·일행 정보에서 똑같다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY' }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.getAllByText('정문 앞 (학교 정문)', { exact: true }).length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByText('학교 정문', { exact: true })).toBeNull()
  })

  it('일행이 바뀌면 지금 할 일 아래에 새 몫과 변경 이탈을 바로 보여 준다', () => {
    render(<PartyScreen party={{ ...base, status: 'READY', canLeaveAfterChange: true, changeExitFree: true, groupVersion: 2 }} now={now} myId={1} run={vi.fn()} />)
    expect(screen.getByText('일행이 바뀌었어요')).toBeTruthy()
    expect(screen.getByRole('button', { name: '변경돼서 빠지기' })).toBeTruthy()
  })
})

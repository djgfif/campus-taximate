import React from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import { BoardSection } from './Board.jsx'
import Home from './Home.jsx'
import PaymentCard from './PaymentCard.jsx'
import { FairnessNote } from './ui.jsx'

// 처음 쓰는 학생·교수 관점 후기에서 나온 문제를 고친 동작.
const now = '2026-11-12T00:00:00Z'
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'history').mockResolvedValue([])
  vi.spyOn(api, 'school').mockResolvedValue({ name: '한빛대학교', lat: 37.5, lon: 127 })
  vi.spyOn(api, 'board').mockResolvedValue({ now, parties: [], waiting: [] })
})
afterEach(() => vi.restoreAllMocks())

const party = (extra) => ({ id: 5, status: 'PREPAYING', direction: 'FROM_HUB', anchorName: '정문', meetingPoint: '정류장',
  departureAt: '2026-11-12T01:00:00Z', payer: false, myPrepaid: false, unreadChat: 0, ...extra })
const home = (parties) => render(<Home state={{ now, parties, restricted: false }} myId={1} onStart={vi.fn()} onReopen={vi.fn()} onHistory={vi.fn()} onMenu={vi.fn()} />)

describe('진행 중 띠는 내 할 일 기준으로 말한다', () => {
  it('아직 내지 않았으면 선결제가 필요하다고 한다', async () => {
    home([party()])
    expect(await screen.findByText('선결제가 필요해요')).toBeTruthy()
  })

  it('나는 냈고 다른 사람을 기다리면 기다린다고 한다', async () => {
    home([party({ myPrepaid: true })])
    expect(await screen.findByText('일행이 결제하는 중이에요')).toBeTruthy()
    expect(screen.queryByText('선결제가 필요해요')).toBeNull()
  })
})

describe('모집 중 목록', () => {
  it('고른 방향이 비었으면 반대 방향에 모집 중인 일행을 알려 주고 바로 바꾼다', async () => {
    api.board.mockResolvedValue({ now, waiting: [], parties: [{ id: 1, direction: 'FROM_HUB', anchorName: '정문', meetingPoint: '정류장',
      departureAt: '2026-11-12T01:00:00Z', joinUntil: '2026-11-12T00:50:00Z', riders: 2, seats: 2, people: [] }] })
    const onSwitch = vi.fn()
    render(<BoardSection now={now} direction="TO_SCHOOL" onSwitch={onSwitch} />)
    fireEvent.click(await screen.findByRole('button', { name: /하교 일행 1개가 모집 중이에요/ }))
    expect(onSwitch).toHaveBeenCalled()
  })
})

describe('이 일행이 정해진 이유', () => {
  it('내 숫자와 서버 기준을 나란히 보인다', () => {
    render(<FairnessNote delay={4} solo={10000} share={6000} rules={{ maxDelayMinutes: 15, minSavingRate: 0.1 }} />)
    expect(screen.getByText('4분')).toBeTruthy()
    expect(screen.getByText('15분 이내만 묶어요')).toBeTruthy()
    expect(screen.getByText('60%')).toBeTruthy()
    expect(screen.getByText('90% 이하만 묶어요')).toBeTruthy()
  })
})

describe('지금 할 일 안의 선결제', () => {
  const order = { orderId: 'p-1', amount: 3680, purpose: 'PREPAY', status: 'REQUIRED', deadlineAt: '2026-11-12T00:05:00Z', approvedAmount: 0, refundedAmount: 0, simulated: true }

  it('10분 안쪽 남은 시간은 급하다고 표시한다', () => {
    const view = render(<PaymentCard compact partyId={1} myId={2} now={now} config={{ demo: true }} clientKey="" knownOrder={order} />)
    expect(view.container.querySelector('.left.urgent').textContent).toContain('5분 남음')
  })

  it('결제를 마치면 기한과 내역을 접고 승인 완료만 남긴다', () => {
    render(<PaymentCard compact partyId={1} myId={2} now={now} config={{ demo: true }} clientKey=""
      knownOrder={{ ...order, status: 'APPROVED', approvedAmount: order.amount }} />)
    expect(screen.getByText('승인 완료')).toBeTruthy()
    expect(screen.queryByText(/기한/)).toBeNull()
    expect(screen.queryByText('주문 금액')).toBeNull()
  })
})

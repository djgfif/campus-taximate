import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import Home from './Home.jsx'

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(() => vi.restoreAllMocks())

const settled = { id: 7, status: 'COMPLETED', members: [], payerId: 1 }
const state = (now) => ({ now, parties: [], restricted: false, settledParty: settled })

describe('홈', () => {
  it('홈에는 채팅을 그리지 않고, 정산 끝난 일행은 내역으로 연다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([])
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ messages: [], closed: true })
    vi.spyOn(api, 'partyMembers').mockResolvedValue([])
    const props = { myId: 1, onStart: vi.fn(), onReopen: vi.fn(), onHistory: vi.fn(), onMenu: vi.fn() }
    const view = render(<Home {...props} state={state('2026-11-12T09:00:00Z')} />)
    for (let i = 1; i <= 4; i++) {
      view.rerender(<Home {...props} state={state(`2026-11-12T09:0${i}:00Z`)} />)
    }
    await waitFor(() => expect(screen.getByText('최근 정산 내역 보기')).toBeTruthy())
    expect(screen.queryAllByRole('region', { name: '일행 채팅' })).toHaveLength(0)
  })

  it('App이 학교를 넘기면 홈은 학교를 다시 읽지 않고, 학교가 도착해도 이용 내역을 한 번만 읽는다', async () => {
    const history = vi.spyOn(api, 'history').mockResolvedValue([])
    const school = vi.spyOn(api, 'school').mockResolvedValue({ name: '학교', lat: 37.8, lon: 127.1 })
    vi.spyOn(api, 'board').mockResolvedValue({ parties: [], waiting: [] })
    const props = { myId: 1, onStart: vi.fn(), onReopen: vi.fn(), state: state('2026-11-12T09:00:00Z') }
    // App이 학교를 읽는 중(null) → 읽은 뒤(값)
    const view = render(<Home {...props} school={null} />)
    view.rerender(<Home {...props} school={{ name: '학교', lat: 37.8, lon: 127.1 }} />)
    await waitFor(() => expect(history).toHaveBeenCalled())
    expect(history).toHaveBeenCalledTimes(1)
    expect(school).not.toHaveBeenCalled()
  })

  it('홈은 지도를 마운트하지 않고 요청 중심 화면을 렌더링한다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([])
    vi.spyOn(api, 'board').mockResolvedValue({ parties: [], waiting: [] })
    const { container } = render(
      <Home myId={1} onStart={vi.fn()} onReopen={vi.fn()} state={state('2026-11-12T09:00:00Z')} />,
    )
    expect(await screen.findByRole('heading', { name: '오늘 어디로 가나요?' })).toBeTruthy()
    expect(container.querySelector('.map-canvas')).toBeNull()
    expect(container.querySelector('.home-map')).toBeNull()
  })

  it('미확인 후불 정산이 있으면 진행 중인 이용에서 확인·결제 안내를 먼저 보여 준다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([])
    vi.spyOn(api, 'board').mockResolvedValue({ parties: [], waiting: [] })
    const activeParty = {
      id: 91,
      paymentFlowVersion: 3,
      status: 'OBJECTION_WINDOW',
      direction: 'FROM_HUB',
      anchorName: '역전 정류장',
      meetingPoint: '정문 앞',
      departureAt: '2026-11-12T09:30:00Z',
      payer: false,
      myRequestId: 10,
      unreadChat: 0,
      members: [{ requestId: 10, pointName: '사거리 편의점' }],
      settlement: {
        myConfirmed: false,
        payable: true,
        autoFinalizeAt: '2026-11-12T15:30:00Z',
        objectionDeadlineAt: '2026-11-12T15:30:00Z',
      },
    }
    const view = render(<Home myId={1} onStart={vi.fn()} onReopen={vi.fn()} state={{ now: '2026-11-12T10:00:00Z', parties: [activeParty], restricted: false }} />)
    expect(await screen.findByText('정산 금액을 확인하고 결제해 주세요')).toBeTruthy()
    expect(screen.getByText(/확인 마감까지/)).toBeTruthy()
    expect(screen.queryByRole('region', { name: '이용 방법' })).toBeNull()
    const html = view.container.innerHTML
    expect(html.indexOf('정산 금액을 확인하고 결제해 주세요')).toBeLessThan(html.indexOf('이용 방법') === -1 ? Infinity : html.indexOf('이용 방법'))
  })
})

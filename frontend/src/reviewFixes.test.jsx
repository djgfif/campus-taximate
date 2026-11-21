import React from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import { BoardSection } from './Board.jsx'
import Home from './Home.jsx'
import PaymentCard from './PaymentCard.jsx'
import { FairnessNote } from './ui.jsx'
import { DriverView, PartyScreen } from './PartyScreens.jsx'
import { PayerInput, SettlementScreen } from './SettleScreens.jsx'
import { AdminScreen } from './ReviewScreens.jsx'
import { DemoPresenter } from './DemoBar.jsx'
import MapPicker from './MapPicker.jsx'

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
    expect(await screen.findByText('선결제를 해 주세요')).toBeTruthy()
  })

  it('나는 냈고 다른 사람을 기다리면 기다린다고 한다', async () => {
    home([party({ myPrepaid: true })])
    expect(await screen.findByText('다른 사람들이 결제하는 중이에요')).toBeTruthy()
    expect(screen.queryByText('선결제를 해 주세요')).toBeNull()
  })
  it('공통 승차(FROM_HUB)로 만든 등교 기록도 서버가 정한 표시 방향대로 등교 탭 최근 경로에 보인다', async () => {
    // 등교/하교 구분은 서버(BoardService.displayDirection)가 정해 이용 내역에 displayDirection으로 보낸다.
    api.history.mockResolvedValue([
      { id: 1, direction: 'FROM_HUB', displayDirection: 'TO_SCHOOL', anchorName: '역전 정류장', pointName: '한빛대학교' },
      { id: 2, direction: 'FROM_HUB', displayDirection: 'FROM_HUB', anchorName: '정문', pointName: '의정부역' },
    ])
    const onStart = vi.fn()
    render(<Home state={{ now, parties: [], restricted: false }} myId={1} onStart={onStart} onReopen={vi.fn()} />)
    const button = await screen.findByRole('button', { name: /한빛대학교/ })
    expect(screen.queryByRole('button', { name: /의정부역/ })).toBeNull()
    fireEvent.click(button)
    expect(onStart).toHaveBeenCalledWith(expect.objectContaining({ direction: 'TO_SCHOOL' }))
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
    expect(screen.getByText('90% 이하일 때만 일행이 돼요')).toBeTruthy()
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

describe('다이얼로그 접근성', () => {
  it('기사님 화면과 지도 선택창은 열릴 때 포커스를 옮기고 Escape로 닫힌다', () => {
    const closeDriver = vi.fn()
    const view = render(<DriverView party={{ direction: 'FROM_HUB', anchorName: '정문', payerId: 1, members: [{ requestId: 1, memberId: 1, rideOrder: 1, pointName: '역전' }] }} onClose={closeDriver} />)
    expect(document.activeElement).toBe(screen.getByRole('dialog', { name: '기사님께 보여 줄 화면' }))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(closeDriver).toHaveBeenCalledOnce()
    view.unmount()

    const closeMap = vi.fn()
    render(<MapPicker center={{ lat: 37.5, lon: 127.0 }} title="출발지 고르기" onPick={vi.fn()} onClose={closeMap} />)
    expect(document.activeElement).toBe(screen.getByRole('dialog', { name: '출발지 고르기' }))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(closeMap).toHaveBeenCalledOnce()
  })
})

describe('실사용 점검 후 개선', () => {
  it('홈 진행 중 일행은 승차 대기 위치가 아니라 내 목적지를 경로로 보여 준다', async () => {
    home([party({
      status: 'READY',
      anchorName: '학교 정문',
      meetingPoint: '정문 앞 버스 정류장',
      myRequestId: 10,
      members: [{ requestId: 10, memberId: 1, nickname: '하은', pointName: '역전 버스 정류장', rideOrder: 1 }],
    })])
    expect(await screen.findByText('학교 정문에서 역전 버스 정류장')).toBeTruthy()
    expect(screen.queryByText('학교 정문에서 정문 앞 버스 정류장')).toBeNull()
  })

  it('탑승 번호 마감 전 AWAITING_FARE에서는 결제자에게 5분 대기 이유를 알려 준다', () => {
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, messages: [] })
    vi.spyOn(api, 'partyMembers').mockResolvedValue([])
    vi.spyOn(api, 'boardingState').mockResolvedValue({ code: '123456', expiresAt: '2026-11-12T00:05:00Z', entered: [], remainingSeconds: 300 })
    render(<PartyScreen party={party({
      status: 'AWAITING_FARE',
      payer: true,
      payerId: 1,
      canSettle: false,
      myRequestId: 10,
      prepayDeadlineAt: '2026-11-12T00:00:00Z',
      members: [{ requestId: 10, memberId: 1, nickname: '하은', pointName: '역전', rideOrder: 1 }],
    })} now={now} myId={1} />)
    expect(screen.getByText('탑승 확인이 끝나면 금액을 입력해요')).toBeTruthy()
  })

  it('요금 이의 창에 취소 버튼이 있고, 넓은 화면 정산은 오른쪽에 정산 요약을 보여 준다', () => {
    window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} })
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, messages: [] })
    const settledParty = party({
      status: 'OBJECTION_WINDOW',
      payerId: 2,
      myRequestId: 10,
      expectedTotal: 16800,
      prepayDeadlineAt: '2026-11-11T23:40:00Z',
      members: [{ requestId: 10, memberId: 1, nickname: '가', pointName: '역전', rideOrder: 1 }, { requestId: 11, memberId: 2, nickname: '나', pointName: '터미널', rideOrder: 2 }],
      settlement: { paidAmount: 16800, objectionDeadlineAt: '2026-11-13T00:00:00Z', finalizedAt: null, shares: [{ memberId: 1, amount: 6800 }, { memberId: 2, amount: 10000, payer: true }], payments: [], receiptAvailable: true },
    })
    render(<SettlementScreen party={settledParty} myId={1} now={now} config={{ demo: true }} />)
    expect(screen.getByRole('heading', { name: '정산 요약' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '요금 이의', exact: true }))
    const dialog = screen.getByRole('dialog', { name: '요금 이의' })
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('dialog', { name: '요금 이의' })).toBeNull()
  })

  it('실제 요금 입력에서 일행 탑승 확인을 눌렀을 때 대상이 없으면 빈 안내를 보여 준다', async () => {
    vi.spyOn(api, 'partyBoardingCases').mockResolvedValue([])
    vi.spyOn(api, 'compensation').mockResolvedValue([])
    render(<PayerInput party={party({
      status: 'AWAITING_FARE',
      payer: true,
      payerId: 1,
      myRequestId: 10,
      expectedTotal: 15000,
      prepayDeadlineAt: '2026-11-11T23:40:00Z',
      members: [{ requestId: 10, memberId: 1, nickname: '하은', pointName: '역전', rideOrder: 1 }],
    })} now={now} />)
    fireEvent.click(screen.getByRole('button', { name: '일행 탑승 확인' }))
    expect(await screen.findByText('확인이 필요한 일행이나 보전 내역이 없어요.')).toBeTruthy()
  })

  it('관리자 화면은 모의 운영 장부 경계를 안내하고 발표자 창은 관객 창 열기 버튼을 제공한다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    const view = render(<AdminScreen allowed onBack={vi.fn()} />)
    expect(await screen.findByText(/모의 운영 장부예요/)).toBeTruthy()
    view.unmount()

    vi.spyOn(api, 'demoStatus').mockResolvedValue({ now, partyId: null, investigations: [] })
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)
    render(<DemoPresenter now={now} run={vi.fn()} parties={[]} />)
    fireEvent.click(screen.getByRole('button', { name: '관객 창 열기' }))
    expect(openSpy).toHaveBeenCalledWith('/', 'taximate-audience')
  })
})

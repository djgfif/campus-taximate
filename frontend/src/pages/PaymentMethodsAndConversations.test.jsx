import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import PaymentMethodsPage from './PaymentMethodsPage.jsx'
import MessagesPage from './MessagesPage.jsx'
import { RequestForm } from '../RequestFlow.jsx'
import { api, ApiError } from '../api.js'
import { isPaymentParty, isPostpaidParty } from '../format.js'

vi.mock('../MapView.jsx', () => ({ default: () => <div>지도</div> }))
vi.mock('../MapPicker.jsx', () => ({ default: () => <div>위치 고르기</div>, searchPlaces: vi.fn().mockResolvedValue([]) }))

const now = '2026-11-25T09:00:00Z'
const card = { id: 1, provider: 'SIMULATED', cardLabel: '테스트 카드 ****1234', active: true, registeredAt: '2026-11-25T08:00:00Z' }

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.sessionStorage.clear()
})

function Probe({ label }) {
  const location = useLocation()
  return <p>{label}:{location.state?.returnTo || ''}</p>
}

function renderPayments(config, entry = '/app/account/payments') {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/app/account/payments" element={<PaymentMethodsPage config={config} onBack={vi.fn()} />} />
        <Route path="/request/home" element={<p>요청 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('결제 일행 판정', () => {
  it('v0은 결제 일행이 아니고 v2·v3은 결제 일행이다', () => {
    expect(isPaymentParty(null)).toBe(false)
    expect(isPaymentParty({ status: 'CONFIRMED', prepayDeadlineAt: null })).toBe(false)
    expect(isPostpaidParty({ status: 'CONFIRMED' })).toBe(false)
    expect(isPaymentParty({ status: 'CONFIRMED', paymentFlowVersion: 2 })).toBe(true)
    expect(isPostpaidParty({ status: 'CONFIRMED', paymentFlowVersion: 2 })).toBe(false)
    const v3 = { status: 'COMPLETED', prepayDeadlineAt: null, paymentFlowVersion: 3 }
    expect(isPaymentParty(v3)).toBe(true)
    expect(isPostpaidParty(v3)).toBe(true)
  })
})

describe('결제수단 관리', () => {
  it('활성 결제수단만 마스킹된 이름·종류·등록일로 보이고 빌링 키는 보이지 않는다', async () => {
    vi.spyOn(api, 'paymentMethods').mockResolvedValue([
      { ...card, billingKey: 'secret-billing-key' },
      { id: 2, provider: 'TOSS_BILLING', cardLabel: '지운 카드 ****9999', active: false, registeredAt: now },
    ])
    renderPayments({ demo: false })
    expect(await screen.findByText('테스트 카드 ****1234')).toBeTruthy()
    expect(screen.getByText('테스트')).toBeTruthy()
    expect(screen.queryByText(/secret-billing-key/)).toBeNull()
    expect(screen.queryByText('지운 카드 ****9999')).toBeNull()
    expect(screen.getByRole('button', { name: '돌아가기' })).toBeTruthy()
  })

  it('시연 환경에서는 카드번호 없는 키로 테스트 카드를 한 번에 등록한다', async () => {
    vi.spyOn(api, 'paymentMethods').mockResolvedValueOnce([]).mockResolvedValue([card])
    const registerSpy = vi.spyOn(api, 'registerPaymentMethod').mockResolvedValue(card)
    renderPayments({ demo: true })
    expect(await screen.findByText('등록된 결제수단이 없어요')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '테스트 카드 등록' }))
    await waitFor(() => expect(registerSpy).toHaveBeenCalledOnce())
    const body = registerSpy.mock.calls[0][0]
    expect(body.provider).toBe('SIMULATED')
    expect(body.cardLabel).toBe('테스트 카드 ****1234')
    expect(body.billingKey).toMatch(/^demo-billing-/)
    expect(body.billingKey).not.toMatch(/\d{13,}/)
    expect(await screen.findByText('테스트 카드 ****1234')).toBeTruthy()
  })

  it('운영 환경은 토스 자동결제만 고를 수 있고 카드번호 원문은 보내지 않는다', async () => {
    vi.spyOn(api, 'paymentMethods').mockResolvedValue([])
    const registerSpy = vi.spyOn(api, 'registerPaymentMethod').mockResolvedValue({ ...card, provider: 'TOSS_BILLING' })
    renderPayments({ demo: false, simulatedPayment: false })
    await screen.findByText('등록된 결제수단이 없어요')
    expect(screen.queryByRole('button', { name: '테스트 카드 등록' })).toBeNull()
    const key = screen.getByLabelText('빌링 키')
    fireEvent.change(key, { target: { value: '4111-1111-1111-1111' } })
    expect(screen.getByText(/카드번호는 입력하지 마세요/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '결제수단 등록' }).disabled).toBe(true)
    fireEvent.change(key, { target: { value: 'bk_test_abc' } })
    fireEvent.click(screen.getByRole('button', { name: '결제수단 등록' }))
    await waitFor(() => expect(registerSpy).toHaveBeenCalledWith(expect.objectContaining({ provider: 'TOSS_BILLING', billingKey: 'bk_test_abc' })))
  })

  it('삭제를 확인하면 지우고, 미납·진행 중 이용 때문에 거절되면 이유를 보여 준다', async () => {
    vi.spyOn(api, 'paymentMethods').mockResolvedValue([card])
    const removeSpy = vi.spyOn(api, 'deletePaymentMethod')
      .mockRejectedValueOnce(new ApiError(409, 'PAYMENT_OVERDUE', '미납'))
      .mockRejectedValueOnce(new ApiError(409, 'ACTIVE_RIDE_REQUIRES_PAYMENT_METHOD', '진행 중'))
      .mockResolvedValueOnce(null)
    renderPayments({ demo: true })
    const removeOnce = async () => {
      fireEvent.click(await screen.findByRole('button', { name: '테스트 카드 ****1234 삭제' }))
      fireEvent.click(screen.getByRole('button', { name: '삭제하기' }))
    }
    await removeOnce()
    expect(await screen.findByText('미납된 결제가 있어 삭제할 수 없어요')).toBeTruthy()
    await removeOnce()
    expect(await screen.findByText('진행 중인 요청이나 일행이 있어 마지막 결제수단은 삭제할 수 없어요')).toBeTruthy()
    await removeOnce()
    expect(await screen.findByText('결제수단을 삭제했어요')).toBeTruthy()
    expect(removeSpy).toHaveBeenCalledTimes(3)
    expect(removeSpy).toHaveBeenLastCalledWith(1)
  })

  it('요청 화면에서 왔으면 등록 뒤 요청 이어서 작성으로 돌아간다', async () => {
    vi.spyOn(api, 'paymentMethods').mockResolvedValueOnce([]).mockResolvedValue([card])
    vi.spyOn(api, 'registerPaymentMethod').mockResolvedValue(card)
    renderPayments({ demo: true }, { pathname: '/app/account/payments', state: { returnTo: '/request/home' } })
    expect(await screen.findByRole('button', { name: '요청 화면으로 돌아가기' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '테스트 카드 등록' }))
    fireEvent.click(await screen.findByRole('button', { name: '요청 이어서 작성' }))
    expect(await screen.findByText('요청 화면')).toBeTruthy()
  })
})

describe('독립 메시지함 대화 목록', () => {
  const conversation = {
    partyId: 7, routeLabel: '정문에서 의정부역', departureAt: '2026-11-25T09:30:00Z', status: 'READY', memberCount: 3,
    lastMessage: '정문 앞에 도착했어요', lastMessageAt: '2026-11-25T09:05:00Z', unreadChat: 2, writable: true,
  }

  it('서버 대화 목록의 마지막 메시지와 안 읽은 수를 보이고 새 채팅 알림에 다시 읽는다', async () => {
    const spy = vi.spyOn(api, 'conversations').mockResolvedValue([conversation])
    render(<MessagesPage state={{ now, parties: [] }} activePartyId={null} onSelectParty={vi.fn()} onOpenTrip={vi.fn()} onHome={vi.fn()} />)
    expect(await screen.findByText('정문 앞에 도착했어요')).toBeTruthy()
    expect(screen.getByText('정문에서 의정부역')).toBeTruthy()
    expect(screen.getByLabelText('안 읽은 메시지 2개')).toBeTruthy()
    expect(spy).toHaveBeenCalledTimes(1)
    window.dispatchEvent(new CustomEvent('taximate:chat', { detail: 7 }))
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(2))
  })

  it('대화 목록을 읽지 못하면 진행 중 일행 목록으로 그대로 보여 준다', async () => {
    const spy = vi.spyOn(api, 'conversations').mockRejectedValue(new ApiError(500, 'ERROR', '실패'))
    const party = {
      id: 42, direction: 'FROM_HUB', anchorName: '대진대', status: 'READY', departureAt: '2026-11-25T09:30:00Z', myRequestId: 101, unreadChat: 1,
      members: [{ requestId: 101, pointName: '포천시청' }],
    }
    const onSelect = vi.fn()
    render(<MessagesPage state={{ now, parties: [party] }} activePartyId={null} onSelectParty={onSelect} onOpenTrip={vi.fn()} onHome={vi.fn()} />)
    await waitFor(() => expect(spy).toHaveBeenCalled())
    fireEvent.click(screen.getByText('대진대에서 포천시청'))
    expect(onSelect).toHaveBeenCalledWith(42)
    expect(screen.getByLabelText('안 읽은 메시지 1개')).toBeTruthy()
  })
})

describe('요청 작성 중 결제수단 가드', () => {
  const hubs = [{ id: 1, name: '정문', lat: 37.8, lon: 127.1 }]
  const config = { demo: true, paymentFlowVersion: 3, sampleDropoffs: [{ name: '역전', lat: 37.9, lon: 127.2 }] }
  const props = { now: '2026-11-11T00:00:00Z', config, run: vi.fn().mockResolvedValue(), onClose: vi.fn() }

  beforeEach(() => {
    vi.spyOn(api, 'hubs').mockResolvedValue(hubs)
    vi.spyOn(api, 'school').mockResolvedValue({ name: '학교', lat: 37.8, lon: 127.1 })
    vi.spyOn(api, 'nearbyPoints').mockResolvedValue([{ id: 1, name: '승차 지점 1', walkSeconds: 60 }, { id: 2, name: '승차 지점 2', walkSeconds: 120 }])
    vi.spyOn(api, 'previewRequest').mockResolvedValue({ soloFare: 16800, soloMinutes: 30, resultBy: '2026-11-11T00:05:00Z' })
    vi.spyOn(api, 'board').mockResolvedValue({ parties: [], waiting: [] })
    props.run.mockClear(); props.onClose.mockClear()
  })

  const fillRoute = async () => {
    fireEvent.change(await screen.findByLabelText('시연 출발지'), { target: { value: '0' } })
    fireEvent.change(screen.getByLabelText('시연 도착지'), { target: { value: '0' } })
    await screen.findAllByRole('checkbox')
  }

  it('409 PAYMENT_METHOD_REQUIRED면 안내와 결제수단 등록 버튼을 보이고, 작성 내용을 보관한 채 이동한다', async () => {
    vi.spyOn(api, 'paymentMethods').mockResolvedValue([card])
    vi.spyOn(api, 'createRequest').mockRejectedValue(new ApiError(409, 'PAYMENT_METHOD_REQUIRED', '결제수단이 필요해요'))
    render(
      <MemoryRouter initialEntries={['/request/home']}>
        <Routes>
          <Route path="/request/home" element={<RequestForm {...props} />} />
          <Route path="/app/account/payments" element={<Probe label="결제수단 화면" />} />
        </Routes>
      </MemoryRouter>,
    )
    await fillRoute()
    fireEvent.change(screen.getByLabelText('일행이 알아볼 메모 (선택)'), { target: { value: '검은 백팩' } })
    await screen.findByText('15,200원 이하')
    fireEvent.click(screen.getByRole('button', { name: '일행 찾기' }))
    expect(await screen.findByText('결제수단을 먼저 등록해 주세요')).toBeTruthy()
    expect(props.onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '결제수단 등록하기' }))
    expect(await screen.findByText('결제수단 화면:/request/home')).toBeTruthy()
    const draft = JSON.parse(window.sessionStorage.getItem('taximate:request-draft:FROM_HUB'))
    expect(draft).toMatchObject({ origin: { name: '정문' }, destination: { name: '역전' }, memo: '검은 백팩', pointIds: [1] })
  })

  it('결제수단이 없으면 미리 알리고, 보관한 작성 내용을 돌아왔을 때 복원한다', async () => {
    window.sessionStorage.setItem('taximate:request-draft:FROM_HUB', JSON.stringify({
      origin: { name: '정문', point: { lat: 37.8, lon: 127.1 } }, destination: { name: '역전', point: { lat: 37.9, lon: 127.2 } },
      mode: 'RESERVATION', timeBasis: 'DEPART', memo: '검은 백팩', pointIds: [2],
    }))
    vi.spyOn(api, 'paymentMethods').mockResolvedValue([])
    render(<RequestForm {...props} />)
    expect(await screen.findByText('결제수단을 먼저 등록해 주세요')).toBeTruthy()
    expect(screen.getByRole('textbox', { name: /^출발지/ }).value).toBe('정문')
    expect(screen.getByRole('textbox', { name: /^도착지/ }).value).toBe('역전')
    expect(screen.getByLabelText('일행이 알아볼 메모 (선택)').value).toBe('검은 백팩')
    const choices = await screen.findAllByRole('checkbox')
    expect(choices.map((c) => c.checked)).toEqual([false, true])
    expect(screen.getByRole('button', { name: '일행 찾기' }).disabled).toBe(true)
    // 닫으면 보관한 작성 내용을 지운다.
    fireEvent.click(screen.getByRole('button', { name: '뒤로' }))
    expect(props.onClose).toHaveBeenCalledOnce()
    expect(window.sessionStorage.getItem('taximate:request-draft:FROM_HUB')).toBeNull()
  })
})

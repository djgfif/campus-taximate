import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RequestTimePicker from './RequestTimePicker.jsx'
import { requestTimeOptions } from './format.js'
import { PartyScreen } from './PartyScreens.jsx'
import { PaymentRecord } from './PaymentCard.jsx'
import { Situation } from './RequestStatus.jsx'
import History from './History.jsx'
import KakaoTaxi from './KakaoTaxi.jsx'
import { api } from './api.js'
vi.mock('./MapView.jsx', () => ({ default: () => <div>지도</div> }))
const now = '2026-11-12T00:00:00Z'
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: true, messages: [] })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
describe('날짜·시각 선택', () => {
  it('오늘/내일, 시·분과 빠른 선택에 유효한 5분 단위만 사용한다', () => {
    const change = vi.fn()
    render(<RequestTimePicker label="출발 가능 시작" options={requestTimeOptions(now)} now={now} value="2026-11-12T00:30:00.000Z" onChange={change} />)
    expect(screen.getByRole('button', { name: '오늘', exact: true })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '내일', exact: true }))
    expect(change).toHaveBeenLastCalledWith('2026-11-13T00:30:00.000Z')
    fireEvent.click(screen.getByRole('button', { name: '1시간 뒤', exact: true }))
    expect(change).toHaveBeenLastCalledWith('2026-11-12T01:00:00.000Z')
    expect(screen.getByLabelText('출발 가능 시작 시').options.length).toBeLessThanOrEqual(24)
    expect(screen.getByLabelText('출발 가능 시작 분').options.length).toBeLessThanOrEqual(12)
  })
  it('자정 직전에도 다음 날 23:55를 넘는 선택지는 없다', () => {
    const at = '2026-11-12T14:58:00Z'
    const options = requestTimeOptions(at)
    expect(options.at(-1)).toBe('2026-11-13T14:55:00.000Z')
    expect(options.every((t) => new Date(t).getTime() % 300000 === 0)).toBe(true)
  })
})
describe('학생 안내', () => {
  const party = { id: 1, status: 'PREPAYING', departureAt: '2026-11-12T01:00:00Z', prepayDeadlineAt: '2026-11-12T00:40:00Z', canArrive: true, canCancel: true, payer: true, myRequestId: 1, members: [{ requestId: 1, memberId: 1, nickname: '나', rideOrder: 1 }], myShare: 1000 }
  it('고정 영역에는 주요 버튼 하나만 있고 보조 동작은 본문에 있다', () => {
    const view = render(<PartyScreen party={party} now={now} myId={1} run={vi.fn()} />)
    expect(view.container.querySelectorAll('.foot button')).toHaveLength(1)
    const secondary = screen.getByRole('region', { name: '일행 보조 메뉴' })
    expect(within(secondary).getByRole('button', { name: '내 탑승 취소' })).toBeTruthy()
    expect(within(secondary).getByRole('button', { name: '홈으로' })).toBeTruthy()
    expect(screen.getByText(/나는 선결제하지 않아요/)).toBeTruthy()
    expect(screen.queryByText(/결제자 외 일행은/)).toBeNull()
    expect(screen.getByRole('link', { name: '카카오T 열기' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '택시 불렀어요' })).toBeTruthy()
  })
  it('카카오T 앱 링크를 열 때 웹 안내도 제공한다', () => {
    render(<KakaoTaxi />)
    const launch = screen.getByRole('link', { name: '카카오T 열기' })
    expect(launch.getAttribute('href')).toBe('kakaot://launch')
    launch.addEventListener('click', (e) => e.preventDefault())
    fireEvent.click(launch)
    expect(screen.getByRole('link', { name: '카카오T 웹 안내' }).getAttribute('href')).toContain('kakaomobility.com')
  })
  it('승계 당사자는 수락과 거절을 답할 수 있다', async () => {
    const accept = vi.spyOn(api, 'payerSuccession').mockResolvedValue()
    render(<PartyScreen party={{ ...party, status: 'PAYER_PENDING', successorId: 1, successionDeadlineAt: now }} now={now} myId={1} run={async (fn) => fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '결제자 이어받기' }))
    await waitFor(() => expect(accept).toHaveBeenCalledWith(1, true))
  })
  it('선결제 전에는 0원 승인 대신 아직 결제 전을 안내한다', () => {
    render(<PaymentRecord order={{ amount: 2000, approvedAmount: 0, status: 'REQUIRED' }} />)
    expect(screen.getByText('아직 결제 전')).toBeTruthy()
    expect(screen.queryByText('승인 금액')).toBeNull()
  })
  it('과거 다음 확인 시각은 현재 확인 중으로 바꾼다', () => {
    render(<Situation now={now} insight={{ stage: 'ALONE', nextCheckAt: '2026-11-11T23:55:00Z' }} />)
    expect(screen.getByText(/지금 조건을 다시 확인하고 있어요/)).toBeTruthy()
    expect(screen.queryByText(/다음 확인은/)).toBeNull()
  })
  it('미래 시각은 서버 시각 기준으로 표시한다', () => {
    render(<Situation now={now} insight={{ stage: 'ALONE', nextCheckAt: '2026-11-12T00:05:00Z' }} />)
    expect(screen.getByText(/다음 확인은/)).toBeTruthy()
  })
  it('학생 이력은 정확도 통계를 조회하지 않는다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([])
    const stats = vi.spyOn(api, 'fareAccuracy').mockResolvedValue({ settled: 1, within: 0 })
    render(<History />)
    await screen.findByText('아직 이용 내역이 없어요')
    expect(stats).not.toHaveBeenCalled()
  })
  it('관리자 이력은 통계를 조회한다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([])
    const stats = vi.spyOn(api, 'fareAccuracy').mockResolvedValue({ settled: 1, within: 0 })
    render(<History admin />)
    await waitFor(() => expect(stats).toHaveBeenCalledOnce())
  })
})

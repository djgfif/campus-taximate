import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DemoBar from './DemoBar.jsx'
import AuthScreen from './AuthScreen.jsx'
import DemoAccounts from './DemoAccounts.jsx'
import { api } from './api.js'

const progress = { now: '2026-11-12T00:00:00Z', partyId: null, stage: '요청 전', next: '탑승 요청 만들기', role: null, investigations: [] }
const prepaying = { id: 7, status: 'PREPAYING', payer: false }
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'demoStatus').mockResolvedValue(progress)
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
const run = async (action) => action()
async function panel(parties = [], status = progress) {
  api.demoStatus.mockResolvedValue(status)
  render(<DemoBar now={progress.now} run={run} parties={parties} />)
  fireEvent.click(screen.getByRole('button', { name: '시연', exact: true }))
  await screen.findByText(/^장면 \d \/ 7/)
}

describe('시연 패널', () => {
  it('일행이 없으면 첫 장면에서 역할을 골라 일행을 만든다. 서버 상태 이름은 보이지 않는다', async () => {
    const match = vi.spyOn(api, 'demoMatch').mockResolvedValue({ message: '시연 일행을 만들었어요' })
    await panel()
    expect(screen.getByText(/^장면 1 \/ 7/)).toBeTruthy()
    expect(screen.getByRole('heading', { name: '길 올리고 일행 찾기' })).toBeTruthy()
    expect(screen.queryByText(/현재 단계/)).toBeNull()
    expect(screen.queryByRole('button', { name: '다음 장면 보기' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '선결제하는 일행' }))
    fireEvent.click(screen.getByRole('button', { name: '일행 만들기' }))
    await waitFor(() => expect(match).toHaveBeenCalledWith('PASSENGER'))
    await screen.findByText('시연 일행을 만들었어요')
  })
  it('일행이 있으면 지금 장면과 눌러 볼 곳을 알려 주고 다음 장면으로 넘긴다', async () => {
    const next = vi.spyOn(api, 'demoNext').mockResolvedValue({ ...progress, partyId: 7, stage: '출발 준비' })
    await panel([prepaying], { ...progress, partyId: 7, stage: '선결제 대기' })
    expect(screen.getByText(/^장면 2 \/ 7/)).toBeTruthy()
    expect(screen.getByText('일행방 아래 카드에서 선결제를 해 보세요.')).toBeTruthy()
    const scenes = screen.getByRole('list', { name: '장면 순서' })
    expect(within(scenes).getByText('2 일행 확정, 선결제').closest('li').getAttribute('aria-current')).toBe('step')
    expect(screen.queryByRole('button', { name: '일행 만들기' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '다음 장면 보기' }))
    await waitFor(() => expect(next).toHaveBeenCalledOnce())
  })
  it('결제자가 안 온 장면은 이어받기 버튼을 눌러 보라고 한다', async () => {
    await panel([{ id: 7, status: 'PAYER_PENDING', payer: false }], { ...progress, partyId: 7 })
    expect(screen.getByRole('heading', { name: '결제자가 오지 않았어요' })).toBeTruthy()
    expect(screen.getByText(/제가 결제할게요/)).toBeTruthy()
  })
  it.each([
    ['바로 출발 일행', 'INSTANT'], ['한 명이 안 왔을 때', 'NO_SHOW'], ['나중에 한 명이 더 탈 때', 'JOIN'], ['요금에 이의를 낼 때', 'FARE_OBJECTION'],
    ['결제자가 안 왔을 때', 'PAYER_NO_SHOW'], ['내가 결제를 이어받을 때', 'MY_SUCCESSION'], ['탔는지 답이 없을 때', 'RIDE_UNCONFIRMED'],
  ])('다른 장면 "%s"를 한 번 요청한다', async (label, id) => {
    const scenario = vi.spyOn(api, 'demoScenario').mockResolvedValue(progress)
    await panel()
    fireEvent.click(screen.getByRole('button', { name: label, exact: true }))
    await waitFor(() => expect(scenario).toHaveBeenCalledWith(id, 'PAYER'))
    expect(scenario).toHaveBeenCalledOnce()
  })
  it('노쇼 판정 결과를 내부 번호 대신 닉네임으로 표시한다', async () => {
    await panel([], { ...progress, investigations: [{ memberId: 4, nickname: '지민', status: 'CONFIRMED', deadlineAt: progress.now }] })
    expect(screen.getByText(/지민님: 노쇼로 확정/)).toBeTruthy()
    expect(screen.queryByText(/회원 4/)).toBeNull()
  })
  it('계정 바꾸기는 시연 창 안에 있다', async () => {
    render(<DemoBar now={progress.now} run={run} accounts={<DemoAccounts onLogin={vi.fn()} />} />)
    fireEvent.click(screen.getByRole('button', { name: '시연', exact: true }))
    expect(await screen.findByText('계정 바꾸기')).toBeTruthy()
    expect(screen.getByRole('region', { name: '시연 계정 빠른 전환' })).toBeTruthy()
  })
})
describe('시연 계정', () => {
  it('운영 로그인 화면에는 시연 계정을 노출하지 않는다', () => {
    render(<AuthScreen config={{ demo: false }} onLogin={vi.fn()} />)
    expect(screen.queryByRole('region', { name: '시연 계정 빠른 전환' })).toBeNull()
  })
  it('시연 로그인에는 9개 계정을 제공한다', () => {
    render(<AuthScreen config={{ demo: true }} onLogin={vi.fn()} />)
    expect(within(screen.getByRole('region', { name: '시연 계정 빠른 전환' })).getAllByRole('button')).toHaveLength(9)
  })
  it('관리자로 전환하면 세션 갱신 콜백을 실행한다', async () => {
    const login = vi.spyOn(api, 'demoLogin').mockResolvedValue()
    const done = vi.fn()
    render(<DemoAccounts onLogin={done} />)
    fireEvent.click(screen.getByRole('button', { name: 'demo-admin' }))
    await waitFor(() => expect(done).toHaveBeenCalledOnce())
    expect(login).toHaveBeenCalledWith('demo-admin')
  })
})

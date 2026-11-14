import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DemoBar from './DemoBar.jsx'
import AuthScreen from './AuthScreen.jsx'
import DemoAccounts from './DemoAccounts.jsx'
import { api } from './api.js'

const progress = { now: '2026-11-12T00:00:00Z', stage: '선결제 대기', next: '선결제 기한', role: '나는 일행원 (선결제 대상)', investigations: [] }
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'demoStatus').mockResolvedValue(progress)
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
const run = async (action) => action()
async function panel() {
  render(<DemoBar now={progress.now} run={run} />)
  fireEvent.click(screen.getByRole('button', { name: '시연', exact: true }))
  await screen.findByText('현재 단계: 선결제 대기')
}

describe('시연 패널', () => {
  it('역할을 선택해 일행을 만들고 내 역할과 다음 단계를 보여 준다', async () => {
    const match = vi.spyOn(api, 'demoMatch').mockResolvedValue({ message: '나는 일행원이에요' })
    const next = vi.spyOn(api, 'demoNext').mockResolvedValue({ ...progress, stage: '출발 준비', next: '택시 호출 시각' })
    await panel()
    fireEvent.click(screen.getByRole('button', { name: '내가 결제자 아님(선결제 체험)' }))
    fireEvent.click(screen.getByRole('button', { name: '일행 만들기' }))
    await waitFor(() => expect(match).toHaveBeenCalledWith('PASSENGER'))
    await screen.findByText('나는 일행원이에요')
    expect(screen.getByText('나는 일행원 (선결제 대상)')).toBeTruthy()
    api.demoStatus.mockResolvedValue({ ...progress, stage: '출발 준비', next: '택시 호출 시각' })
    fireEvent.click(screen.getByRole('button', { name: '다음 단계로' }))
    await waitFor(() => expect(next).toHaveBeenCalledOnce())
    await screen.findByText('다음 단계: 택시 호출 시각')
  })
  it.each([
    ['즉시 매칭', 'INSTANT'], ['노쇼 발생', 'NO_SHOW'], ['나중 일행 합류', 'JOIN'], ['요금 이의', 'FARE_OBJECTION'],
    ['결제자 노쇼·승계 요청', 'PAYER_NO_SHOW'], ['내가 결제자 승계', 'MY_SUCCESSION'], ['운행 미확인 (T+5분)', 'RIDE_UNCONFIRMED'],
  ])('%s 시나리오를 한 번 요청한다', async (label, id) => {
    const scenario = vi.spyOn(api, 'demoScenario').mockResolvedValue(progress)
    await panel()
    fireEvent.click(within(screen.getByRole('region', { name: '시나리오' })).getByRole('button', { name: label, exact: true }))
    await waitFor(() => expect(scenario).toHaveBeenCalledWith(id, 'PAYER'))
    expect(scenario).toHaveBeenCalledOnce()
  })
  it('노쇼 판정 결과를 표시한다', async () => {
    api.demoStatus.mockResolvedValue({ ...progress, investigations: [{ memberId: 4, status: 'CONFIRMED', deadlineAt: progress.now }] })
    await panel()
    expect(screen.getByText(/회원 4: 노쇼 확정/)).toBeTruthy()
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

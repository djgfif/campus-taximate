import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import { AdminScreen, BoardingCasesScreen, PartyBoardingPanel } from './ReviewScreens.jsx'
import { PayerInput } from './SettleScreens.jsx'

const now = '2026-11-18T03:00:00Z'
const investigation = { id: 7, partyId: 11, memberId: 2, status: 'PENDING', deadlineAt: '2026-11-19T03:00:00Z' }
const caseItem = { investigation, explanation: null, votes: [] }
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('탑승 조사·소명', () => {
  it('소명 실패를 알리고 초안을 보존하며 성공한 소명만 접수한 내용으로 표시한다', async () => {
    vi.spyOn(api, 'boardingCases').mockResolvedValueOnce([caseItem]).mockResolvedValue([{ ...caseItem, explanation: { explanation: '실제로 탔습니다' } }])
    const explain = vi.spyOn(api, 'explainBoarding').mockRejectedValueOnce(new Error('잠시 뒤 다시 시도해 주세요')).mockResolvedValue(null)
    render(<BoardingCasesScreen now={now} onBack={() => {}} />)
    const input = await screen.findByRole('textbox', { name: '당시 상황을 알려 주세요' })
    fireEvent.change(input, { target: { value: '실제로 탔습니다' } })
    fireEvent.click(screen.getByRole('button', { name: '소명 제출' }))
    expect((await screen.findByRole('alert')).textContent).toContain('잠시 뒤')
    expect(input.value).toBe('실제로 탔습니다')
    fireEvent.click(screen.getByRole('button', { name: '소명 제출' }))
    await screen.findByText('접수한 소명')
    expect(screen.queryByRole('button', { name: '소명 제출' })).toBeNull()
    expect(explain).toHaveBeenLastCalledWith(7, '실제로 탔습니다')
  })

  it('마감된 조사는 소명을 제출하도록 안내하지 않는다', async () => {
    vi.spyOn(api, 'boardingCases').mockResolvedValue([{ ...caseItem, investigation: { ...investigation, deadlineAt: '2026-11-17T03:00:00Z' } }])
    const explain = vi.spyOn(api, 'explainBoarding')
    render(<BoardingCasesScreen now={now} onBack={() => {}} />)
    await screen.findByText('소명 접수 기간이 끝났어요.')
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(explain).not.toHaveBeenCalled()
  })

  it('작성 중 자동 조회가 실패해도 소명 초안과 입력창을 유지한다', async () => {
    vi.spyOn(api, 'boardingCases').mockResolvedValueOnce([caseItem]).mockRejectedValue(new Error('일시적 연결 실패'))
    render(<BoardingCasesScreen now={now} onBack={() => {}} />)
    const input = await screen.findByRole('textbox', { name: '당시 상황을 알려 주세요' })
    fireEvent.change(input, { target: { value: '아직 작성 중인 상황 설명' } })
    fireEvent(window, new Event('taximate:state'))
    await screen.findByText('일시적 연결 실패')
    expect(screen.getByRole('textbox', { name: '당시 상황을 알려 주세요' })).toBe(input)
    expect(input.value).toBe('아직 작성 중인 상황 설명')
  })

  it('일행 표시는 최종 확인 뒤에만 보내고 본인 표시와 중복 제출 버튼은 닫는다', async () => {
    vi.spyOn(api, 'partyBoardingCases').mockResolvedValue([{ ...caseItem, nickname: '일행', investigation: { ...investigation, memberId: 3 } }])
    vi.spyOn(api, 'compensation').mockResolvedValue([])
    const vote = vi.spyOn(api, 'boardingVote').mockResolvedValue(null)
    const party = { id: 11, myRequestId: 12, payerId: 2, startedAt: now, members: [
      { memberId: 2, requestId: 12, nickname: '나' },
    ] }
    render(<PartyBoardingPanel party={party} now={now} />)
    fireEvent.click(await screen.findByRole('button', { name: '일행 안 왔어요' }))
    expect(vote).not.toHaveBeenCalled()
    fireEvent.click(within(screen.getByRole('group', { name: '탑승 표시 최종 확인' })).getByRole('button', { name: '표시 제출' }))
    await waitFor(() => expect(vote).toHaveBeenCalledOnce())
    expect(vote).toHaveBeenCalledWith(11, 3, false)
    expect(screen.queryByRole('button', { name: '나 안 왔어요' })).toBeNull()
  })

  it('결제자는 영수증 제출 전 금액 입력 화면에서도 제외된 일행의 탑승을 확인할 수 있다', async () => {
    vi.spyOn(api, 'partyBoardingCases').mockResolvedValue([{ ...caseItem, nickname: '빠진 동승자', investigation: { ...investigation, memberId: 3 } }])
    vi.spyOn(api, 'compensation').mockResolvedValue([])
    const party = { id: 11, status: 'AWAITING_FARE', myRequestId: 12, payerId: 2, startedAt: now, expectedTotal: 15000,
      members: [{ memberId: 2, requestId: 12, nickname: '나' }] }
    render(<PayerInput party={party} now={now} onLater={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: '일행 탑승 확인' }))
    await screen.findByRole('button', { name: '빠진 동승자 안 왔어요' })
    expect(screen.getByRole('textbox', { name: '실제 요금' }).value).toBe('')
  })
})

describe('관리자 업무', () => {
  it('저장된 운행 판정과 충돌 주문을 표시하고 재판정 입력을 제공하지 않는다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    vi.spyOn(api, 'adminFareReviews').mockResolvedValue([{
      partyId: 11, reason: 'RIDE_FINANCIAL_HOLD', rideDecision: 'OPERATED', objections: [],
      financialConflicts: [{ orderId: 'order-11', approvedAmount: 6000, refundedAmount: 6000, pendingRefundAmount: 0 }],
    }])
    render(<AdminScreen allowed onBack={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: '요금·운행' }))
    await screen.findByText('운행 판정 저장됨')
    expect(screen.getByText('order-11')).toBeTruthy()
    expect(screen.getAllByText('6,000원')).toHaveLength(2)
    expect(screen.queryByRole('combobox', { name: '운행 판정' })).toBeNull()
    expect(screen.queryByRole('button', { name: /판정 저장|검토 확정/ })).toBeNull()
  })

  it('일반 회원에게 관리자 API를 요청하지 않는다', () => {
    const load = vi.spyOn(api, 'adminNoShows')
    render(<AdminScreen allowed={false} onBack={() => {}} />)
    expect(screen.getByText('관리자만 이용할 수 있어요.')).toBeTruthy()
    expect(load).not.toHaveBeenCalled()
  })

  it('노쇼 자료를 불러오고 필수 근거와 선택한 결론을 함께 제출한다', async () => {
    const summary = { investigation: { ...investigation, status: 'REVIEW' }, absentVotes: 1, presentVotes: 0, receiptPresent: false, explanationPresent: true }
    vi.spyOn(api, 'adminNoShows').mockResolvedValueOnce([summary]).mockResolvedValue([])
    vi.spyOn(api, 'adminNoShow').mockResolvedValue({ explanation: { explanation: '휴대폰이 꺼졌습니다' }, votes: [], chat: [{ id: 4, memberId: 3, kind: 'IMAGE', imageUrl: '/api/admin/no-shows/7/messages/4/image', createdAt: now }] })
    const decide = vi.spyOn(api, 'decideNoShow').mockResolvedValue(null)
    render(<AdminScreen allowed onBack={() => {}} />)
    await screen.findByText('휴대폰이 꺼졌습니다')
    fireEvent.click(screen.getByText('일행 표시와 최근 대화 확인'))
    expect(screen.getByRole('link', { name: '사진 증거 보기' }).getAttribute('href')).toBe('/api/admin/no-shows/7/messages/4/image')
    expect(screen.getByRole('button', { name: '노쇼 판정 저장' }).disabled).toBe(true)
    fireEvent.change(screen.getByRole('combobox', { name: '판정' }), { target: { value: 'NOT_NO_SHOW' } })
    fireEvent.change(screen.getByRole('textbox', { name: '처리 근거 (필수)' }), { target: { value: '동승자 진술과 소명 확인' } })
    fireEvent.click(screen.getByRole('button', { name: '노쇼 판정 저장' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith(7, { decision: 'NOT_NO_SHOW', evidence: '동승자 진술과 소명 확인' }))
    await screen.findByText('처리할 항목이 없어요.')
  })

  it('요금 수정 중 입력 초점을 유지하고 실제 요금과 판정 근거를 전송한다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    vi.spyOn(api, 'adminFareReviews').mockResolvedValue([{ partyId: 11, amount: 15000, objections: [{ id: 1, reason: '영수증과 금액이 달라요' }] }])
    const decide = vi.spyOn(api, 'decideFare').mockResolvedValue(null)
    render(<AdminScreen allowed onBack={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: '요금·운행' }))
    fireEvent.change(await screen.findByRole('combobox', { name: '요금 이의 판정' }), { target: { value: 'accept' } })
    const input = screen.getByRole('textbox', { name: '확정할 실제 요금' })
    input.focus()
    fireEvent.change(input, { target: { value: '14000' } })
    expect(document.activeElement).toBe(input)
    fireEvent.change(screen.getByRole('textbox', { name: '처리 근거 (필수)' }), { target: { value: '영수증 확인' } })
    fireEvent.click(screen.getByRole('button', { name: '요금 판정 저장' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith(11, { accept: true, correctedAmount: 14000, evidence: '영수증 확인' }))
  })

  it('승차 지점 저장 실패 후 같은 내용의 재시도에는 같은 멱등키를 사용한다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    vi.spyOn(api, 'adminHubs').mockResolvedValue([])
    const save = vi.spyOn(api, 'saveHub').mockRejectedValueOnce(new Error('응답 지연')).mockResolvedValue({ id: 1 })
    render(<AdminScreen allowed onBack={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: '승차 지점', exact: true }))
    fireEvent.click(await screen.findByRole('button', { name: '승차 지점 등록' }))
    for (const [name, value] of [['지점 이름', '정문'], ['정확히 만날 자리', '정문 앞 택시 승차대'], ['위도', '37.8'], ['경도', '127.1'], ['학교 번호 (여러 곳이면 쉼표로 구분)', '1'], ['처리 근거 (필수)', '현장 확인']]) {
      fireEvent.change(screen.getByRole('textbox', { name }), { target: { value } })
    }
    fireEvent.click(screen.getByRole('button', { name: '승차 지점 저장' }))
    await screen.findByText('응답 지연')
    fireEvent.click(screen.getByRole('button', { name: '승차 지점 저장' }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2))
    expect(save.mock.calls[0][2]).toBe(save.mock.calls[1][2])
    expect(save.mock.calls[1][1].hub).toMatchObject({ name: '정문', lat: 37.8, lon: 127.1, schoolIds: [1], fieldChecked: false })
  })
})

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AuthScreen from './AuthScreen.jsx'
import { BoardSection } from './Board.jsx'
import BoardingCodePanel from './BoardingCodePanel.jsx'
import History from './History.jsx'
import { PartyChat } from './PartyChat.jsx'
import { PartyShell } from './PartyShell.jsx'
import { RequestForm } from './RequestFlow.jsx'
import { AdminScreen } from './ReviewScreens.jsx'
import { PayerInput } from './SettleScreens.jsx'
import { api } from './api.js'
import { Sheet } from './ui.jsx'

vi.mock('./MapView.jsx', () => ({ default: () => <div>지도</div> }))
vi.mock('./MapPicker.jsx', () => ({ default: () => <div>위치 고르기</div>, searchPlaces: vi.fn().mockResolvedValue([]) }))

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:preview')
  globalThis.URL.revokeObjectURL = vi.fn()
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('디자인·편의성 근본 개선 검증', () => {
  it('로그인 화면에서 비밀번호 보기 토글, 8자 안내, Enter 제출이 동작한다', async () => {
    const signin = vi.spyOn(api, 'signin').mockResolvedValue({})
    const onLogin = vi.fn().mockResolvedValue()
    render(<AuthScreen config={{ schools: [{ name: '대진대학교', emailDomain: 'daejin.ac.kr' }] }} onLogin={onLogin} />)
    const email = screen.getByLabelText('학교 메일')
    const pw = screen.getByLabelText('비밀번호')
    fireEvent.change(email, { target: { value: 'student@daejin.ac.kr' } })
    fireEvent.change(pw, { target: { value: '1234' } })
    expect(screen.getByText('8자 이상 입력해 주세요.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '입력 내용 보기' }))
    expect(pw.type).toBe('text')
    fireEvent.change(pw, { target: { value: '12345678' } })
    expect(screen.queryByText('8자 이상 입력해 주세요.')).toBeNull()
    fireEvent.submit(pw.closest('form'))
    await waitFor(() => expect(signin).toHaveBeenCalledWith({ email: 'student@daejin.ac.kr', password: '12345678' }))
    expect(onLogin).toHaveBeenCalledWith(false)
  })

  it('Sheet 모달에 닫기(X) 버튼이 있어 클릭하면 닫힌다', () => {
    const onClose = vi.fn()
    render(<Sheet title="안내" onClose={onClose}><p>본문</p></Sheet>)
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('요청 화면에서 장소 지우기(X), 펼쳐진 시간 카드, 출발 범위 빠른 칩이 동작한다', async () => {
    vi.spyOn(api, 'hubs').mockResolvedValue([{ id: 1, name: '정문', lat: 37.8, lon: 127.1 }])
    vi.spyOn(api, 'school').mockResolvedValue({ name: '학교', lat: 37.8, lon: 127.1 })
    vi.spyOn(api, 'nearbyPoints').mockResolvedValue([{ id: 1, name: '정문 앞', walkSeconds: 60, walkEstimated: true }])
    vi.spyOn(api, 'previewRequest').mockResolvedValue({ soloFare: 15000, soloMinutes: 25, resultBy: '2026-11-11T00:05:00Z' })
    vi.spyOn(api, 'board').mockResolvedValue({ parties: [], waiting: [] })
    render(<RequestForm now="2026-11-11T00:00:00Z" config={{ demo: true, sampleDropoffs: [{ name: '역전', lat: 37.9, lon: 127.2 }] }} run={vi.fn()} onClose={vi.fn()} />)
    const origin = await screen.findByRole('textbox', { name: /^출발지/ })
    fireEvent.change(origin, { target: { value: '정문' } })
    fireEvent.click(screen.getByRole('button', { name: '출발지 지우기' }))
    expect(origin.value).toBe('')
    // 시간 카드가 기본으로 열려 있어 빠른 선택 버튼이 바로 보인다.
    expect(screen.getByRole('button', { name: '30분 뒤' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '30분' }))
    expect(screen.getByLabelText('출발 가능 범위').value).toBe('30')
  })

  it('탑승 코드 패널은 남은 시간 카운트다운과 복사 버튼을 보여 주며 6자리 숫자 형식을 유지한다', async () => {
    vi.spyOn(api, 'boardingState').mockResolvedValue({
      status: 'OPEN', code: '482910', deadlineAt: '2026-11-11T00:05:00Z', generation: 1, remainingAttempts: 5,
    })
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<BoardingCodePanel party={{ id: 1, payer: true, status: 'READY' }} now="2026-11-11T00:02:30Z" />)
    const ticket = await screen.findByLabelText('탑승 코드')
    expect(ticket.textContent).toMatch(/^\d{6}$/)
    expect(screen.getByText('02:30')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '탑승 코드 복사' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('482910'))
  })

  it('일행 방 머리의 인원·상태 줄을 눌러도 일행 정보 창이 열린다', async () => {
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ messages: [], closed: false, hasOlder: false })
    vi.spyOn(api, 'partyBoardingCases').mockResolvedValue([])
    vi.spyOn(api, 'compensation').mockResolvedValue([])
    const party = {
      id: 10, status: 'READY', meetingPoint: '정문 택시 승차대', departureAt: '2026-11-11T01:00:00Z',
      members: [{ memberId: 1, requestId: 1, nickname: '민수' }, { memberId: 2, requestId: 2, nickname: '지민' }],
    }
    render(<PartyShell party={party} now="2026-11-11T00:30:00Z" onHome={vi.fn()} info={<p>정보 본문</p>} />)
    fireEvent.click(screen.getByRole('button', { name: /2명, 모이는 중/ }))
    expect(await screen.findByRole('dialog', { name: '일행 정보' })).toBeTruthy()
  })

  it('채팅 + 도구 메뉴는 바깥 클릭이나 Esc 키로 닫힌다', async () => {
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ messages: [], closed: false, hasOlder: false })
    render(<PartyChat partyId={10} now="2026-11-11T00:30:00Z" status="READY" full />)
    const plus = await screen.findByRole('button', { name: '더 보기' })
    fireEvent.click(plus)
    expect(screen.getByRole('menu', { name: '일행 도구' })).toBeTruthy()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('menu', { name: '일행 도구' })).toBeNull()
    fireEvent.click(plus)
    expect(screen.getByRole('menu', { name: '일행 도구' })).toBeTruthy()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu', { name: '일행 도구' })).toBeNull()
  })

  it('이용 내역에서 열려 있는 첫 항목을 다시 누르면 접힌다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([
      { id: 1, stage: 'SETTLED', direction: 'FROM_HUB', anchorName: '정문', pointName: '역전', windowStart: '2026-11-11T01:00:00Z', soloFare: 15000, paid: 5000, partySize: 3 },
    ])
    render(<History onHome={vi.fn()} />)
    const row = await screen.findByRole('button', { name: /정문/ })
    expect(row.getAttribute('aria-expanded')).toBe('true')
    fireEvent.click(row)
    expect(row.getAttribute('aria-expanded')).toBe('false')
  })

  it('모집 게시판 조회에 실패하면 빈 화면 대신 재시도 안내를 보여 준다', async () => {
    const board = vi.spyOn(api, 'board')
      .mockRejectedValueOnce(new Error('일시적 오류'))
      .mockResolvedValueOnce({ parties: [], waiting: [] })
    render(<BoardSection now="2026-11-11T00:00:00Z" direction="FROM_HUB" />)
    expect(await screen.findByText('모집 중인 일행을 불러오지 못했어요.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
    await waitFor(() => expect(board).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/아직 모인 일행이 없어요/)).toBeTruthy()
  })

  it('영수증 사진 첨부는 5MB 초과를 막고 선택한 사진을 지울 수 있다', () => {
    const party = { id: 1, status: 'AWAITING_FARE', expectedTotal: 15000, myRequestId: 1, members: [{ memberId: 1, requestId: 1, nickname: '나' }] }
    render(<PayerInput party={party} now="2026-11-11T01:00:00Z" onLater={vi.fn()} />)
    const fileInput = screen.getByLabelText('영수증 사진')
    const big = new File(['x'], 'big.png', { type: 'image/png' })
    Object.defineProperty(big, 'size', { value: 6 * 1024 * 1024 })
    fireEvent.change(fileInput, { target: { files: [big] } })
    expect(screen.getByRole('alert').textContent).toContain('5MB 이하')
    const ok = new File(['x'], 'ok.png', { type: 'image/png' })
    fireEvent.change(fileInput, { target: { files: [ok] } })
    expect(screen.getByRole('button', { name: '영수증 사진 지우기' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '영수증 사진 지우기' }))
    expect(screen.queryByRole('button', { name: '영수증 사진 지우기' })).toBeNull()
  })

  it('관리자 처리 기록은 영문 enum 대신 한글 배지로 표시한다', async () => {
    vi.spyOn(api, 'adminNoShows').mockResolvedValue([])
    vi.spyOn(api, 'adminAudits').mockResolvedValue([
      { id: 3, action: 'NO_SHOW_DECISION', targetType: 'BOARDING_CASE', targetId: 7, decision: 'UNDETERMINED', actorId: 9, createdAt: '2026-11-18T03:00:00Z', evidence: '확인 완료' },
    ])
    render(<AdminScreen allowed onBack={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '처리 기록' }))
    expect(await screen.findByText('노쇼 판정')).toBeTruthy()
    expect(screen.getByText('판단 불가 (노쇼 아님)')).toBeTruthy()
    expect(screen.getByText('탑승 조사 #7')).toBeTruthy()
  })
})

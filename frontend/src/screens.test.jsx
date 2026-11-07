import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary.jsx'
import { NoShow, Unmatched } from './RequestStatus.jsx'
import { Sheet } from './ui.jsx'
import { ReportSheet, SettledFeedback } from './MemberSafety.jsx'
import { api } from './api.js'

const latest = {
  id: 1, direction: 'TO_SCHOOL', pointName: '역전', anchorName: '정문', arriveBy: '2026-11-09T00:00:00Z',
  resultBy: '2026-11-08T22:00:00Z', soloFare: 13500, pointLat: 37.8, pointLon: 127.1, anchorLat: 37.9, anchorLon: 127.2,
}

beforeAll(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('끝난 요청 화면', () => {
  const props = { latest, onRetry: () => {}, onHome: () => {}, onMenu: () => {} }

  it('일행을 못 찾았을 때만 그 말을 한다', () => {
    render(<Unmatched {...props} />)
    expect(screen.getByRole('heading').textContent).toContain('일행을 못 찾았어요')
  })

  it('일행이 취소되면 취소라고 말한다', () => {
    render(<Unmatched {...props} latest={{ ...latest, endReason: 'PARTY_CANCELED' }} />)
    expect(screen.getByRole('heading').textContent).toBe('일행이 취소됐어요')
  })

  it('탑승 뒤 혼자 남으면 다시 요청을 권하지 않는다', () => {
    render(<Unmatched {...props} latest={{ ...latest, endReason: 'LEFT_ALONE' }} />)
    expect(screen.getByRole('heading').textContent).toContain('일행이 모두')
    expect(screen.queryByText('같은 경로로 다시 요청')).toBeNull()
  })

  it('노쇼 화면은 택시비를 내지 않는다고 알린다', () => {
    render(<NoShow {...props} />)
    expect(screen.getByText('이번 일행의 택시비는 내지 않습니다.')).toBeTruthy()
  })
})

describe('시트', () => {
  it('Esc로 닫히고 초점이 시트로 옮겨 간다', () => {
    const close = vi.fn()
    render(<Sheet title="확인" onClose={close}><button type="button">예</button></Sheet>)
    expect(document.activeElement).toBe(screen.getByRole('dialog'))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(close).toHaveBeenCalled()
  })
})

describe('오류 경계', () => {
  it('화면이 실패해도 돌아갈 길을 보여 준다', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {})
    const Broken = () => { throw new Error('boom') }
    render(<ErrorBoundary><Broken /></ErrorBoundary>)
    expect(screen.getByText('처음 화면으로')).toBeTruthy()
    quiet.mockRestore()
  })
})


describe('신고와 매칭 설정', () => {
  it('신고 사유 세 개와 200자 메모, 회피 전용 동작을 보여 준다', async () => {
    const report = vi.spyOn(api, 'reportMember').mockResolvedValue(null)
    const avoid = vi.spyOn(api, 'avoidMember').mockResolvedValue(null)
    const saved = vi.fn()
    render(<ReportSheet partyId={11} member={{ memberId: 2, nickname: '일행', avoided: false }} onClose={() => {}} onSaved={saved} />)
    expect(screen.getByRole('dialog', { name: '일행님 신고·다시 매칭 안 함' })).toBeTruthy()
    expect(screen.getAllByRole('radio')).toHaveLength(3)
    expect(screen.getByRole('radio', { name: '불쾌한 언행' }).checked).toBe(true)
    fireEvent.click(screen.getByRole('radio', { name: '위험 행동' }))
    const memo = screen.getByRole('textbox', { name: '메모 (선택, 200자 이하)' })
    expect(memo.maxLength).toBe(200)
    fireEvent.change(memo, { target: { value: '위험했어요' } })
    fireEvent.click(screen.getByRole('button', { name: '신고하기', exact: true }))
    await waitFor(() => expect(saved).toHaveBeenCalledOnce())
    expect(report).toHaveBeenCalledWith(11, 2, { reason: 'DANGEROUS', memo: '위험했어요' })
    expect(avoid).not.toHaveBeenCalled()
    report.mockRestore()
    avoid.mockRestore()
  })

  it('신고 없이 회피만 지정하거나 본인의 지정을 해제할 수 있다', async () => {
    const avoid = vi.spyOn(api, 'avoidMember').mockResolvedValue(null)
    const unavoid = vi.spyOn(api, 'unavoidMember').mockResolvedValue(null)
    const report = vi.spyOn(api, 'reportMember').mockResolvedValue(null)
    const saved = vi.fn()
    const props = { partyId: 11, member: { memberId: 2, nickname: '일행', avoided: false }, onClose: () => {}, onSaved: saved }
    const view = render(<ReportSheet {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '다시 매칭하지 않기', exact: true }))
    await waitFor(() => expect(avoid).toHaveBeenCalledWith(2))
    await waitFor(() => expect(saved).toHaveBeenCalledOnce())
    view.rerender(<ReportSheet {...props} member={{ ...props.member, avoided: true }} />)
    fireEvent.click(screen.getByRole('button', { name: '다시 매칭 안 함 해제', exact: true }))
    await waitFor(() => expect(unavoid).toHaveBeenCalledWith(2))
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(2))
    expect(report).not.toHaveBeenCalled()
    avoid.mockRestore()
    unavoid.mockRestore()
    report.mockRestore()
  })

  it('정산 완료 카드는 괜찮아요를 누르면 서버 저장 없이 한 번 닫힌다', () => {
    localStorage.clear()
    const avoid = vi.spyOn(api, 'avoidMember')
    const report = vi.spyOn(api, 'reportMember')
    const props = { party: { id: 91, status: 'COMPLETED' }, myId: 5 }
    const view = render(<SettledFeedback {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '다음에도 같이 타도 괜찮아요' }))
    expect(screen.queryByRole('region', { name: '정산 완료 후 매칭 설정' })).toBeNull()
    view.unmount()
    render(<SettledFeedback {...props} />)
    expect(screen.queryByText('다음에도 같이 타도 괜찮아요')).toBeNull()
    expect(avoid).not.toHaveBeenCalled()
    expect(report).not.toHaveBeenCalled()
    avoid.mockRestore()
    report.mockRestore()
    localStorage.clear()
  })
})

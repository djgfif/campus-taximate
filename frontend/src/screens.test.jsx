import React from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary.jsx'
import { NoShow, Unmatched } from './RequestStatus.jsx'
import { Sheet } from './ui.jsx'

const latest = {
  id: 1, direction: 'TO_SCHOOL', pointName: '역전', anchorName: '정문', arriveBy: '2026-11-09T00:00:00Z',
  resultBy: '2026-11-08T22:00:00Z', soloFare: 13500, pointLat: 37.8, pointLon: 127.1, anchorLat: 37.9, anchorLon: 127.2,
}

beforeAll(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(cleanup)

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

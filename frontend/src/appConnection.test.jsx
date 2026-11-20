import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { api } from './api.js'

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('서버 연결 실패', () => {
  it('설정과 로그인 정보를 둘 다 못 읽어도 안내는 전체 화면 카드 하나뿐이다(위 알림과 겹치지 않는다)', async () => {
    const down = Object.assign(new Error('연결 실패'), { status: undefined })
    vi.spyOn(api, 'config').mockRejectedValue(down)
    vi.spyOn(api, 'me').mockRejectedValue(down)
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><App /></MemoryRouter>)
    expect(await screen.findByText('서버에 연결하지 못했습니다')).toBeTruthy()
    expect(screen.queryByText(/잠시 뒤 다시 열어 주세요/)).toBeNull()
    expect(document.querySelectorAll('.toast')).toHaveLength(0)
  })
})

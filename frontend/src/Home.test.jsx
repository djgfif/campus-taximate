import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import Home from './Home.jsx'

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(() => vi.restoreAllMocks())

const settled = { id: 7, status: 'COMPLETED', members: [], payerId: 1 }
const state = (now) => ({ now, parties: [], restricted: false, settledParty: settled })

describe('홈', () => {
  it('홈에는 채팅을 그리지 않고, 정산 끝난 일행은 내역으로 연다', async () => {
    vi.spyOn(api, 'history').mockResolvedValue([])
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ messages: [], closed: true })
    vi.spyOn(api, 'partyMembers').mockResolvedValue([])
    const props = { myId: 1, onStart: vi.fn(), onReopen: vi.fn(), onHistory: vi.fn(), onMenu: vi.fn() }
    const view = render(<Home {...props} state={state('2026-11-12T09:00:00Z')} />)
    for (let i = 1; i <= 4; i++) {
      view.rerender(<Home {...props} state={state(`2026-11-12T09:0${i}:00Z`)} />)
    }
    await waitFor(() => expect(screen.getByText('최근 정산 내역 보기')).toBeTruthy())
    expect(screen.queryAllByRole('region', { name: '일행 채팅' })).toHaveLength(0)
  })
})

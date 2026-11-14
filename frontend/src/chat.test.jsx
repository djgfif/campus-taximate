import React from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import { PartyChat } from './PartyChat.jsx'

const NOW = '2026-11-12T09:00:00Z'
const msg = (id, extra) => ({ id, memberId: 2, nickname: '숲', text: `m${id}`, createdAt: NOW, mine: false, kind: 'TEXT', unread: 0, ...extra })

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:preview')
  globalThis.URL.revokeObjectURL = vi.fn()
})
afterEach(() => vi.restoreAllMocks())

describe('카톡 수준 일행 채팅', () => {
  it('안 읽은 사람 수, 날짜 구분선, 시스템 알림, 사진을 보여 주고 맨 아래면 읽음을 알린다', async () => {
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, hasOlder: false, messages: [
      msg(1, { kind: 'SYSTEM', memberId: null, nickname: null, text: '일행이 정해졌어요. 결제자는 강님이에요.' }),
      msg(2, { mine: true, memberId: 1, nickname: '산', text: '정문 앞이에요', unread: 2 }),
      msg(3, { kind: 'IMAGE', text: '', imageUrl: '/api/parties/7/messages/3/image' }),
    ] })
    const read = vi.spyOn(api, 'readPartyMessages').mockResolvedValue({ lastReadId: 3 })
    render(<PartyChat partyId={7} now={NOW} status="PREPAYING" />)
    await screen.findByText('일행이 정해졌어요. 결제자는 강님이에요.')
    expect(screen.getByLabelText('안 읽은 사람 2명').textContent).toBe('2')
    expect(screen.getAllByRole('separator')).toHaveLength(1)
    expect(screen.getByRole('img', { name: '숲님이 보낸 사진' }).getAttribute('src')).toBe('/api/parties/7/messages/3/image')
    await waitFor(() => expect(read).toHaveBeenCalledWith(7, 3))
  })

  it('보낸 메시지는 바로 보이고, 실패하면 다시 보내기로 같은 번호를 다시 보낸다', async () => {
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, messages: [] })
    vi.spyOn(api, 'readPartyMessages').mockResolvedValue({ lastReadId: 0 })
    const send = vi.spyOn(api, 'sendPartyMessage')
      .mockRejectedValueOnce(Object.assign(new Error('네트워크 오류'), { status: 0 }))
      .mockResolvedValueOnce({ id: 9, memberId: 1, nickname: '산', text: '도착했어요', createdAt: NOW, mine: true, kind: 'TEXT', unread: 1 })
    render(<PartyChat partyId={7} now={NOW} status="PREPAYING" />)
    const input = await screen.findByRole('textbox', { name: '일행에게 보낼 메시지' })
    fireEvent.change(input, { target: { value: '도착했어요' } })
    fireEvent.click(screen.getByRole('button', { name: '메시지 보내기' }))
    expect(input.value).toBe('')
    await screen.findByText('전송 실패')
    const firstId = send.mock.calls[0][2]
    fireEvent.click(screen.getByRole('button', { name: '다시 보내기' }))
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2))
    expect(send.mock.calls[1][2]).toBe(firstId)
    await screen.findByLabelText('안 읽은 사람 1명')
    expect(screen.queryByText('전송 실패')).toBeNull()
  })

  it('사진을 고르면 미리 보이고 업로드한다. 형식이 다르면 보내지 않는다', async () => {
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, messages: [] })
    vi.spyOn(api, 'readPartyMessages').mockResolvedValue({ lastReadId: 0 })
    let finish
    const upload = vi.spyOn(api, 'sendPartyImage').mockImplementation(() => new Promise((resolve) => { finish = resolve }))
    const { container } = render(<PartyChat partyId={7} now={NOW} status="PREPAYING" />)
    await screen.findByRole('button', { name: '사진 보내기' })
    const file = container.querySelector('input[type=file]')
    fireEvent.change(file, { target: { files: [new File(['x'], 'a.gif', { type: 'image/gif' })] } })
    expect(upload).not.toHaveBeenCalled()
    await screen.findByText('JPG, PNG, WEBP 사진만 보낼 수 있어요.')
    fireEvent.change(file, { target: { files: [new File(['x'], 'a.png', { type: 'image/png' })] } })
    expect(await screen.findByRole('img', { name: '보내는 중인 사진' })).toBeTruthy()
    expect(upload).toHaveBeenCalledWith(7, expect.any(File), expect.any(String))
    await act(async () => finish({ id: 4, memberId: 1, nickname: '산', text: '', createdAt: NOW, mine: true, kind: 'IMAGE', imageUrl: '/api/parties/7/messages/4/image', unread: 1 }))
    expect(await screen.findByRole('img', { name: '내가 보낸 사진' })).toBeTruthy()
  })

  it('서버의 채팅 신호가 오면 그 일행 채팅만 다시 읽고, 이전 메시지를 이어 붙인다', async () => {
    const read = vi.spyOn(api, 'partyMessages')
      .mockResolvedValueOnce({ closed: false, hasOlder: true, messages: [msg(51), msg(52)] })
      .mockResolvedValueOnce({ closed: false, hasOlder: true, messages: [msg(51), msg(52), msg(53)] })
      .mockResolvedValueOnce({ closed: false, hasOlder: false, messages: [msg(49), msg(50)] })
    vi.spyOn(api, 'readPartyMessages').mockResolvedValue({ lastReadId: 0 })
    render(<PartyChat partyId={7} now={NOW} status="PREPAYING" />)
    await screen.findByText('m52')
    act(() => { window.dispatchEvent(new CustomEvent('taximate:chat', { detail: 8 })) })
    expect(read).toHaveBeenCalledTimes(1)
    act(() => { window.dispatchEvent(new CustomEvent('taximate:chat', { detail: 7 })) })
    await screen.findByText('m53')
    fireEvent.click(screen.getByRole('button', { name: '이전 메시지 보기' }))
    await screen.findByText('m49')
    expect(read).toHaveBeenLastCalledWith(7, null, 51)
    expect(screen.queryByRole('button', { name: '이전 메시지 보기' })).toBeNull()
  })

  it('휴대폰에서는 Enter가 줄바꿈이고 보내지 않는다', async () => {
    window.matchMedia = (q) => ({ matches: q === '(pointer: coarse)', addEventListener() {}, removeEventListener() {} })
    vi.spyOn(api, 'partyMessages').mockResolvedValue({ closed: false, messages: [] })
    const send = vi.spyOn(api, 'sendPartyMessage').mockResolvedValue({ id: 1 })
    render(<PartyChat partyId={7} now={NOW} status="PREPAYING" />)
    const input = await screen.findByRole('textbox', { name: '일행에게 보낼 메시지' })
    fireEvent.change(input, { target: { value: '첫 줄' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(send).not.toHaveBeenCalled()
  })
})

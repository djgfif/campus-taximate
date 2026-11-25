import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import PublicLayout from '../layouts/PublicLayout.jsx'
import AuthScreen from '../AuthScreen.jsx'
import SettlementPage from './SettlementPage.jsx'
import { api } from '../api.js'

const now = '2026-11-25T10:00:00Z'

const members = [
  { requestId: 101, memberId: 1, nickname: '가학생', pointName: '역전', rideOrder: 1 },
  { requestId: 102, memberId: 2, nickname: '나학생', pointName: '시청', rideOrder: 2 },
  { requestId: 103, memberId: 3, nickname: '다학생', pointName: '터미널', rideOrder: 3 },
]

const baseParty = {
  id: 55,
  status: 'OBJECTION_WINDOW',
  direction: 'FROM_HUB',
  members,
  myRequestId: 101,
  myShare: 3200,
  payer: false,
  payerId: 3,
  payerNickname: '다학생',
  anchorName: '대진대 정문',
  meetingPoint: '정문 앞',
  expectedTotal: 16800,
  departureAt: '2026-11-25T09:00:00Z',
  prepayDeadlineAt: '2026-11-25T08:40:00Z',
  freeCancelUntil: '2026-11-25T08:40:00Z',
  unreadChat: 2,
  settlement: {
    paidAmount: 16800,
    canCorrect: false,
    objectionDeadlineAt: '2026-11-26T10:00:00Z',
    finalizedAt: null,
    shares: [
      { memberId: 1, amount: 3200 },
      { memberId: 2, amount: 5600 },
      { memberId: 3, amount: 8000, payer: true },
    ],
    payments: [],
    receiptAvailable: true,
  },
}

beforeEach(() => {
  window.matchMedia = () => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} })
  vi.spyOn(api, 'partyPeople').mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('PR 4 공개 랜딩 레이아웃과 정산 상세 페이지', () => {
  it('공개 레이아웃은 서비스 핵심 가치 소개와 인증 폼을 함께 렌더링한다', () => {
    render(
      <PublicLayout>
        <AuthScreen config={{ schools: [{ name: '대진대학교', emailDomain: 'daejin.ac.kr' }] }} onLogin={vi.fn()} />
      </PublicLayout>,
    )

    expect(screen.getByText('캠퍼스 택시메이트')).toBeTruthy()
    expect(screen.getByText('학교 메일 인증 학생 전용')).toBeTruthy()
    expect(screen.getByText('공정 기준 자동 매칭')).toBeTruthy()
    expect(screen.getByText('영수증 기반 투명 정산')).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
  })

  it('정산 상세 페이지는 채팅 입력창 없이 정산 요약·요금 이의·메시지함 링크를 제공한다', async () => {
    const objectSpy = vi.spyOn(api, 'objectToFare').mockResolvedValue({})
    const onMessages = vi.fn()
    const onUpdated = vi.fn().mockResolvedValue()

    render(
      <SettlementPage
        party={baseParty}
        myId={1}
        now={now}
        config={{ demo: false }}
        onUpdated={onUpdated}
        onHome={vi.fn()}
        onDetail={vi.fn()}
        onMessages={onMessages}
      />,
    )

    expect(screen.getByText('정산 요약')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: '일행에게 보낼 메시지' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /일행 메시지 열기/ }))
    expect(onMessages).toHaveBeenCalledWith(55)

    fireEvent.click(screen.getByRole('button', { name: '요금 이의' }))
    const dialog = screen.getByRole('dialog', { name: '요금 이의' })
    fireEvent.change(within(dialog).getByLabelText('이의 사유'), { target: { value: '영수증 금액 확인 요청' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '이의 보내기' }))

    await waitFor(() => expect(objectSpy).toHaveBeenCalledWith(55, '영수증 금액 확인 요청'))
    await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1))
  })
})

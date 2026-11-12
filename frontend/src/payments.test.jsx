import React, { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PaymentCard from './PaymentCard.jsx'
import PaymentResult from './PaymentScreens.jsx'
import { callbackPartyId, loadToss, startPayment, TOSS_SDK_URL } from './payments.js'
import { api } from './api.js'

const now = '2026-11-11T00:00:00Z'
const order = { orderId: 'prepay-1', amount: 3680, purpose: 'PREPAY', status: 'REQUIRED', deadlineAt: '2026-11-11T00:10:00Z', approvedAmount: 0, refundedAmount: 0, simulated: true }
beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  sessionStorage.clear()
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); delete window.TossPayments })

describe('선결제 카드', () => {
  it('서버 주문의 금액·115% 안내·기한을 표시하고 시연 모드에서만 모의 승인한다', async () => {
    const create = vi.spyOn(api, 'paymentOrder').mockResolvedValue(order)
    const confirm = vi.spyOn(api, 'confirmPayment').mockResolvedValue({ ...order, status: 'APPROVED', approvedAmount: order.amount })
    const updated = vi.fn().mockResolvedValue()
    render(<PaymentCard partyId={11} myId={2} now={now} config={{ demo: true }} clientKey="" onUpdated={updated} />)
    await screen.findByRole('heading', { name: '선결제 3,680원' })
    expect(create).toHaveBeenCalledWith(11, 'PREPAY')
    expect(screen.getByText('처음 안내된 내 몫의 115%를 미리 결제해요.')).toBeTruthy()
    expect(screen.getByText(/^기한 /).textContent).toContain('10')
    expect(screen.getByText('카드 결제를 준비하지 못했어요. 잠시 뒤 다시 확인해 주세요.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '시연 결제하기' }))
    await waitFor(() => expect(confirm).toHaveBeenCalledWith({ orderId: order.orderId, amount: 3680, paymentKey: 'demo_ui_' + order.orderId }))
    await waitFor(() => expect(updated).toHaveBeenCalledOnce())
    await waitFor(() => expect(screen.queryByRole('button', { name: '시연 결제하기' })).toBeNull())
  })

  it('키가 없고 시연 모드가 아니면 모의 승인 버튼을 보여 주지 않는다', () => {
    const confirm = vi.spyOn(api, 'confirmPayment')
    render(<PaymentCard partyId={11} myId={2} now={now} knownOrder={order} config={{ demo: false }} clientKey="" />)
    expect(screen.getByText('카드 결제를 준비하지 못했어요. 잠시 뒤 다시 확인해 주세요.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '시연 결제하기' })).toBeNull()
    expect(confirm).not.toHaveBeenCalled()
  })

  it('처리 중인 주문에는 결제 버튼을 다시 열지 않는다', () => {
    render(<PaymentCard partyId={11} myId={2} now={now} knownOrder={{ ...order, status: 'PROCESSING' }} config={{ demo: true }} clientKey="test_ck" />)
    expect(screen.getByText('결제 확인 중이에요. 다시 결제하지 말고 결과를 기다려 주세요.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '카드로 결제하기' })).toBeNull()
  })
})

describe('결제 성공·실패 주소', () => {
  it('성공 URL은 서버 금액 확인 뒤 한 번 승인하고 일행 화면으로 돌아간다', async () => {
    const get = vi.spyOn(api, 'getPayment').mockResolvedValue({ ...order, orderId: 'success-url' })
    const confirm = vi.spyOn(api, 'confirmPayment').mockImplementation(async () => {
      expect(get).toHaveBeenCalledWith('success-url')
      return { ...order, status: 'APPROVED', approvedAmount: 3680 }
    })
    const back = vi.fn()
    const updated = vi.fn()
    render(<StrictMode><PaymentResult search="?payment=success&partyId=11&orderId=success-url&paymentKey=test_payment&amount=3680" myId={2} onUpdated={updated} onReturn={back} /></StrictMode>)
    await screen.findByRole('heading', { name: '결제 승인을 확인했어요' })
    expect(get).toHaveBeenCalledOnce()
    expect(confirm).toHaveBeenCalledOnce()
    expect(confirm).toHaveBeenCalledWith({ orderId: 'success-url', paymentKey: 'test_payment', amount: 3680 })
    expect(updated).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '일행 화면으로' }))
    expect(back).toHaveBeenCalledWith(11)
  })

  it('URL 금액이 서버 주문과 다르면 승인을 보내지 않는다', async () => {
    vi.spyOn(api, 'getPayment').mockResolvedValue(order)
    const confirm = vi.spyOn(api, 'confirmPayment')
    render(<PaymentResult search="?payment=success&orderId=wrong-amount&paymentKey=test_payment&amount=1" myId={2} onReturn={() => {}} />)
    await screen.findByRole('alert')
    expect(screen.getByText('주문 금액과 결제 결과 금액이 달라요.')).toBeTruthy()
    expect(confirm).not.toHaveBeenCalled()
  })

  it('실패 URL은 사유를 보여 주고 승인 API를 호출하지 않는다', () => {
    const confirm = vi.spyOn(api, 'confirmPayment')
    render(<PaymentResult search="?payment=fail&message=사용자%20취소&partyId=11" myId={2} onReturn={() => {}} />)
    expect(screen.getByText('사용자 취소')).toBeTruthy()
    expect(confirm).not.toHaveBeenCalled()
  })

  it('승인 통신 실패 뒤 다시 확인은 같은 주문으로 승인 재시도한다', async () => {
    vi.spyOn(api, 'getPayment').mockResolvedValue(order)
    const confirm = vi.spyOn(api, 'confirmPayment').mockRejectedValueOnce(new Error('잠시 연결 실패')).mockResolvedValue({ ...order, status: 'APPROVED' })
    render(<PaymentResult search="?payment=success&orderId=retry-url&paymentKey=retry_payment&amount=3680" myId={2} onReturn={() => {}} />)
    await screen.findByText('잠시 연결 실패')
    fireEvent.click(screen.getByRole('button', { name: '승인 상태 다시 확인' }))
    await screen.findByRole('heading', { name: '결제 승인을 확인했어요' })
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(confirm.mock.calls[0]).toEqual(confirm.mock.calls[1])
  })
})

describe('토스 SDK 연결', () => {
  it('서버 주문 금액과 카드 방식, 같은 출처 성공·실패 주소를 전달한다', async () => {
    const requestPayment = vi.fn().mockResolvedValue()
    const payment = vi.fn().mockReturnValue({ requestPayment })
    window.TossPayments = vi.fn().mockReturnValue({ payment })
    await startPayment(order, 2, 11, 'test_client_key')
    expect(window.TossPayments).toHaveBeenCalledWith('test_client_key')
    expect(payment).toHaveBeenCalledWith({ customerKey: 'taximate_member_2' })
    const args = requestPayment.mock.calls[0][0]
    expect(args).toMatchObject({ method: 'CARD', orderId: order.orderId, amount: { currency: 'KRW', value: 3680 } })
    expect(new URL(args.successUrl).origin).toBe(location.origin)
    expect(new URL(args.successUrl).searchParams.get('payment')).toBe('success')
    expect(new URL(args.failUrl).searchParams.get('payment')).toBe('fail')
    expect(callbackPartyId('?orderId=' + order.orderId)).toBe(11)
  })

  it('standard v2 스크립트를 동적으로 한 번만 만들고 모의 load로 끝낸다', async () => {
    const first = loadToss()
    const second = loadToss()
    const scripts = document.querySelectorAll('script[src="' + TOSS_SDK_URL + '"]')
    expect(scripts).toHaveLength(1)
    window.TossPayments = vi.fn()
    scripts[0].dispatchEvent(new Event('load'))
    expect(await first).toBe(window.TossPayments)
    expect(await second).toBe(window.TossPayments)
    scripts[0].remove()
  })
})

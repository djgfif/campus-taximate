import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { startPayment } from './payments.js'
import { formatDateTime, won } from './format.js'
import { Btn, Rows, Say } from './ui.jsx'

export function PaymentRecord({ order }) {
  const refundPending = Math.max(0, (order.refundRequestedAmount || 0) - (order.refundedAmount || 0))
  return <Rows items={[
    ['주문 금액', won(order.amount)], ['승인 금액', won(order.approvedAmount || 0)],
    order.deadlineAt && ['결제 기한', formatDateTime(order.deadlineAt)],
    ['결제 상태', ({ REQUIRED: '결제 대기', PROCESSING: '확인 중', APPROVED: '승인 완료', EXPIRED: '기한 종료', OVERDUE: '추가 결제 기한 지남', ADMIN_REQUIRED: '관리자 확인 필요' })[order.status] || '상태 확인 중'],
    order.refundedAmount > 0 && ['환불 처리 금액', won(order.refundedAmount)],
    refundPending > 0 && ['환불 처리 중', won(refundPending)],
  ]} />
}

export default function PaymentCard({ partyId, purpose = 'PREPAY', myId, now, config, onUpdated, knownOrder, readOnly = false, clientKey = import.meta.env.VITE_TOSS_CLIENT_KEY || '' }) {
  const key = [partyId, purpose, myId].join(':')
  const [loaded, setLoaded] = useState({})
  const [reload, setReload] = useState(0)
  const [actionError, setActionError] = useState('')
  useEffect(() => {
    if (knownOrder) return undefined
    let closed = false
    api.paymentOrder(partyId, purpose).then((order) => !closed && setLoaded({ key, order }))
      .catch((e) => !closed && setLoaded({ key, error: e.message }))
    return () => { closed = true }
  }, [key, partyId, purpose, now, reload, knownOrder])
  const order = knownOrder || (loaded.key === key ? loaded.order : null)
  const error = actionError || (loaded.key === key ? loaded.error : '')
  const extra = purpose === 'ADDITIONAL'
  const late = order && now && new Date(order.deadlineAt) <= new Date(now)
  const payable = order && ['REQUIRED', 'OVERDUE'].includes(order.status) && (extra || !late) && !readOnly
  const pay = async (simulated) => {
    setActionError('')
    try {
      if (simulated) {
        const next = await api.confirmPayment({ orderId: order.orderId, amount: order.amount, paymentKey: 'demo_ui_' + order.orderId })
        setLoaded({ key, order: next }); await onUpdated?.()
      } else await startPayment(order, myId, partyId, clientKey)
    } catch (e) { setActionError(e.message || '결제를 시작하지 못했어요.') }
  }
  return <section className="payment-card" aria-label={extra ? '추가 결제' : '선결제'}>
    <h2>{extra ? '추가 결제' : '선결제'}{order ? ' ' + won(order.amount) : ''}</h2>
    {!extra && <p>확정 당시 예상 분담금의 115%를 미리 결제해요.</p>}
    {!order && !error && <p role="status">주문을 확인하고 있어요.</p>}
    {order && <>
      <p>기한 {formatDateTime(order.deadlineAt)}</p>
      <PaymentRecord order={order} />
      {order.status === 'APPROVED' ? <Say tone="save-tone">승인 완료</Say>
        : order.status === 'PROCESSING' ? <Say>결제 확인 중이에요. 다시 결제하지 말고 결과를 기다려 주세요.</Say>
          : order.status === 'ADMIN_REQUIRED' ? <Say tone="stop-tone">관리자 확인이 필요한 결제예요.</Say>
            : order.status === 'EXPIRED' || (!extra && late) ? <Say>선결제 기한이 지났어요.</Say>
              : !['REQUIRED', 'OVERDUE'].includes(order.status) ? <Say>결제 상태를 확인하고 있어요.</Say> : null}
      {extra && late && order.status !== 'APPROVED' && <Say tone="stop-tone">추가 결제 기한이 지났어요. 결제를 마치면 새 요청 제한이 풀려요.</Say>}
      {(order.refundRequestedAmount > 0 || order.refundedAmount > 0) && <p className="note">환불은 카드사에 반영되기까지 3~4영업일 정도 걸려요.</p>}
      {payable && (clientKey ? <Btn onClick={() => pay(false)}>카드로 결제하기</Btn> : <>
        <Say>결제 테스트 키 없음</Say>
        {config?.demo && order.simulated && <Btn onClick={() => pay(true)}>모의 결제로 승인하기</Btn>}
      </>)}
    </>}
    {error && <p role="alert">{error}</p>}
    {!knownOrder && <Btn className="small quiet" onClick={() => { setActionError(''); setReload((n) => n + 1) }}>주문 다시 확인</Btn>}
  </section>
}

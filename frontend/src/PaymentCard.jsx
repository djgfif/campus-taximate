import React, { useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import { startPayment } from './payments.js'
import { formatDateTime, remaining, won } from './format.js'
import { Btn, Rows, Say } from './ui.jsx'

export function PaymentRecord({ order }) {
  const refundPending = Math.max(0, (order.refundRequestedAmount || 0) - (order.refundedAmount || 0))
  return <Rows items={[
    ['주문 금액', won(order.amount)], ['결제한 금액', order.approvedAmount > 0 ? won(order.approvedAmount) : '아직 결제 전'],
    order.deadlineAt && ['결제 기한', formatDateTime(order.deadlineAt)],
    ['결제 상태', ({ REQUIRED: '결제 대기', PROCESSING: '확인 중', APPROVED: '승인 완료', EXPIRED: '기한 종료', OVERDUE: '분담금 결제 기한 지남', ADMIN_REQUIRED: '관리자 확인 필요' })[order.status] || '상태 확인 중'],
    order.refundedAmount > 0 && ['환불 처리 금액', won(order.refundedAmount)],
    refundPending > 0 && ['환불 처리 중', won(refundPending)],
  ]} />
}

export default function PaymentCard({ partyId, purpose = 'PREPAY', myId, now, config, onUpdated, onOrder, knownOrder, readOnly = false, compact = false, clientKey = import.meta.env.VITE_TOSS_CLIENT_KEY || '' }) {
  const key = [partyId, purpose, myId].join(':')
  const [loaded, setLoaded] = useState({})
  const [reload, setReload] = useState(0)
  const [actionError, setActionError] = useState('')
  const onOrderRef = useRef(onOrder)
  useEffect(() => { onOrderRef.current = onOrder }, [onOrder])
  // now(5초 폴링 시계)가 바뀔 때마다 POST /payments/orders를 반복 호출하지 않는다.
  useEffect(() => {
    if (knownOrder) return undefined
    let closed = false
    api.paymentOrder(partyId, purpose).then((order) => !closed && setLoaded({ key, order }))
      .catch((e) => !closed && setLoaded({ key, error: e.message }))
    return () => { closed = true }
  }, [key, partyId, purpose, reload, knownOrder])
  const order = knownOrder || (loaded.key === key ? loaded.order : null)
  const error = actionError || (loaded.key === key ? loaded.error : '')
  // 부모(지금 할 일 카드)가 내 주문 상태에 맞춰 제목을 바꿀 수 있게 알려 준다.
  useEffect(() => { if (order) onOrderRef.current?.(order) }, [order])
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
  // 간단히(지금 할 일 카드 안): 아직 아무것도 결제하지 않은 주문은 금액·기한 한 줄과 버튼만 보인다.
  const plain = compact && order && order.status === 'REQUIRED' && !(order.approvedAmount > 0)
  // 지금 할 일 카드 안에서 결제를 마친 뒤에는 기한·주문 내역을 접고 '승인 완료' 한 줄만 남긴다. 환불이 생기면 내역을 다시 편다.
  const settled = compact && order && order.status === 'APPROVED' && !(order.refundRequestedAmount > 0) && !(order.refundedAmount > 0)
  const minutesLeft = order && now ? (new Date(order.deadlineAt) - new Date(now)) / 60000 : null
  const urgent = minutesLeft != null && minutesLeft > 0 && minutesLeft <= 10
  return <section className={compact ? 'payment-card compact' : 'payment-card'} aria-label={extra ? '분담금 결제' : '선결제'}>
    <h2>{extra ? '분담금 결제' : '선결제'}{order ? ' ' + won(order.amount) : ''}</h2>
    {!extra && !compact && <p>확정 분담금을 선결제해요. 나중에 더 내는 일은 없어요.</p>}
    {!order && !error && <p role="status">주문을 확인하고 있어요.</p>}
    {order && <>
      {settled ? <p className="due paid">승인 완료</p> : <>
        <p className="due">기한 {formatDateTime(order.deadlineAt)}{compact && now && !late && payable && <b className={urgent ? 'left urgent' : 'left'}> {remaining(order.deadlineAt, now)} 남음</b>}</p>
        {!plain && <PaymentRecord order={order} />}
      </>}
      {/* 승인 완료는 위 결제 기록의 '결제 상태' 줄에 이미 나오므로 다시 띄우지 않는다. */}
      {order.status === 'APPROVED' ? null
        : order.status === 'PROCESSING' ? <Say>결제 확인 중이에요. 다시 결제하지 말고 결과를 기다려 주세요.</Say>
          : order.status === 'ADMIN_REQUIRED' ? <Say tone="stop-tone">관리자 확인이 필요한 결제예요.</Say>
            : order.status === 'EXPIRED' || (!extra && late) ? <Say>선결제 기한이 지났어요.</Say>
              : !['REQUIRED', 'OVERDUE'].includes(order.status) ? <Say>결제 상태를 확인하고 있어요.</Say> : null}
      {extra && late && order.status !== 'APPROVED' && <Say tone="stop-tone">분담금 결제 기한이 지났어요. 결제를 마치면 새 요청 제한이 풀려요.</Say>}
      {(order.refundRequestedAmount > 0 || order.refundedAmount > 0) && <p className="note">환불은 카드사에 반영되기까지 3~4영업일 정도 걸려요.</p>}
      {/* 결제 방식은 서버의 주문(order.simulated)만 따른다. 화면에 토스 키가 빌드돼 있어도 모의 결제 서버면 토스 창을 열지 않는다. */}
      {payable && (order.simulated ? <>
        {/* 지금 할 일 카드 안에서는 맨 위 시연 띠가 이미 알려 주므로 반복하지 않는다. */}
        {!compact && <p className="note">시연용 모의 결제예요. 실제 돈은 나가지 않아요.</p>}
        {config?.demo && <Btn onClick={() => pay(true)}>{order ? `${won(order.amount)} 결제하기` : '결제하기'}<small className="btn-note">모의</small></Btn>}
      </> : clientKey ? <Btn onClick={() => pay(false)}>카드로 결제하기</Btn>
        : <Say>카드 결제를 준비하지 못했어요. 잠시 뒤 다시 확인해 주세요.</Say>)}
    </>}
    {error && <p role="alert">{error}</p>}
    {!knownOrder && (!compact || error) && <Btn className="small quiet" onClick={() => { setActionError(''); setReload((n) => n + 1) }}>주문 다시 확인</Btn>}
  </section>
}

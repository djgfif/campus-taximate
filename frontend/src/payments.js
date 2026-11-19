import { api } from './api.js'

export const TOSS_SDK_URL = 'https://js.tosspayments.com/v2/standard'
let sdkPromise
const approvals = new Map()

/** 결제를 시작할 때만 로드하며, 실패한 로드는 다시 시도할 수 있다. */
export function loadToss() {
  if (typeof window.TossPayments === 'function') return Promise.resolve(window.TossPayments)
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = TOSS_SDK_URL
      script.async = true
      const fail = () => { clearTimeout(timer); script.remove(); reject(new Error('결제창을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.')) }
      const timer = setTimeout(fail, 10000)
      script.onerror = fail
      script.onload = () => {
        clearTimeout(timer)
        if (typeof window.TossPayments === 'function') resolve(window.TossPayments)
        else fail()
      }
      document.head.appendChild(script)
    }).catch((e) => { sdkPromise = undefined; throw e })
  }
  return sdkPromise
}

export async function startPayment(order, myId, partyId, clientKey) {
  if (!clientKey) throw new Error('카드 결제를 준비하지 못했어요. 잠시 뒤 다시 확인해 주세요.')
  if (!Number.isSafeInteger(Number(myId)) || Number(myId) <= 0) throw new Error('로그인 정보를 다시 확인해 주세요.')
  try { sessionStorage.setItem('taximate-payment:' + order.orderId, JSON.stringify({ partyId, purpose: order.purpose })) } catch { /* 저장 불가 시에도 같은 출처 콜백으로 돌아온다. */ }
  const success = new URL('/', window.location.origin)
  success.searchParams.set('payment', 'success')
  success.searchParams.set('partyId', String(partyId))
  const failure = new URL(success)
  failure.searchParams.set('payment', 'fail')
  const TossPayments = await loadToss()
  return TossPayments(clientKey).payment({ customerKey: 'taximate_member_' + myId }).requestPayment({
    method: 'CARD', amount: { currency: 'KRW', value: order.amount }, orderId: order.orderId,
    orderName: order.purpose === 'ADDITIONAL' ? '택시메이트 추가 결제' : '택시메이트 선결제',
    successUrl: success.href, failUrl: failure.href,
  })
}

export function callbackValues(search) {
  const params = new URLSearchParams(search)
  const orderId = params.get('orderId') || ''
  const paymentKey = params.get('paymentKey') || ''
  const rawAmount = params.get('amount') || ''
  const amount = Number(rawAmount)
  if (!orderId.trim() || orderId.length > 255 || !paymentKey.trim() || paymentKey.length > 200
    || !/^\d+$/.test(rawAmount) || !Number.isSafeInteger(amount) || amount < 0) throw new Error('결제 결과 주소가 올바르지 않아요. 주문 상태를 확인해 주세요.')
  return { orderId, paymentKey, amount }
}

/** StrictMode·동일 URL 재마운트의 중복 승인을 합친다. 최종 권한·금액 검증은 서버가 한다. */
export function confirmCallback(search, myId) {
  let body
  try { body = callbackValues(search) } catch (e) { return Promise.reject(e) }
  const key = JSON.stringify([myId, body])
  if (!approvals.has(key)) {
    if (approvals.size > 20) approvals.delete(approvals.keys().next().value)
    const promise = api.getPayment(body.orderId).then((order) => {
      if (order.amount !== body.amount) throw new Error('주문 금액과 결제 결과 금액이 달라요.')
      // 재방문도 같은 승인 API를 사용해 서버가 paymentKey·금액의 일치 여부를 검증한다.
      return api.confirmPayment(body)
    }).catch((e) => { approvals.delete(key); throw e })
    approvals.set(key, promise)
  }
  return approvals.get(key)
}

export function callbackPartyId(search) {
  const params = new URLSearchParams(search)
  let value = params.get('partyId')
  try { value = JSON.parse(sessionStorage.getItem('taximate-payment:' + params.get('orderId')))?.partyId || value } catch { /* 주소의 숫자만 사용한다. */ }
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

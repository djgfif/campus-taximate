import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { callbackPartyId, callbackValues, confirmCallback } from './payments.js'
import { Btn, Say, Screen } from './ui.jsx'
import { won } from './format.js'

export default function PaymentResult({ search, myId, onReturn, onUpdated }) {
  const params = new URLSearchParams(search)
  const failed = params.get('payment') === 'fail'
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  useEffect(() => {
    if (failed) return undefined
    let closed = false
    confirmCallback(search, myId).then((next) => {
      if (closed) return
      setResult(next); onUpdated?.()
    }).catch((e) => !closed && setError(e.message))
    return () => { closed = true }
  }, [search, myId, failed, onUpdated])
  const refresh = async () => {
    setRefreshing(true)
    try { setResult(await (error ? confirmCallback(search, myId) : api.getPayment(callbackValues(search).orderId))); setError(''); await onUpdated?.() }
    catch (e) { setError(e.message) }
    finally { setRefreshing(false) }
  }
  const approved = result?.status === 'APPROVED'
  const title = failed ? '결제를 마치지 못했어요' : approved ? '결제 승인을 확인했어요' : result ? '결제 상태를 확인하고 있어요' : error ? '결제 결과를 확인하지 못했어요' : '결제 승인 확인 중'
  return <Screen foot={<Btn onClick={() => onReturn(callbackPartyId(search))}>일행 화면으로</Btn>}>
    <div className="pad"><h1 className="head">{title}</h1>
      {failed && <Say tone="stop-tone">{params.get('message') || '결제가 취소되었거나 완료되지 않았어요.'}</Say>}
      {!failed && !result && !error && <p role="status">잠시만 기다려 주세요. 새 결제를 시작하지 마세요.</p>}
      {approved && <p>{won(result.amount)} 승인 완료</p>}
      {result?.status === 'PROCESSING' && <Say>서버가 결제 결과를 확인하고 있어요. 중복 결제하지 마세요.</Say>}
      {result?.status === 'ADMIN_REQUIRED' && <Say tone="stop-tone">관리자 확인이 필요해요.</Say>}
      {error && <p role="alert">{error}</p>}
      {!failed && (result || error) && <Btn className="line" disabled={refreshing} onClick={refresh}>승인 상태 다시 확인</Btn>}
    </div>
  </Screen>
}

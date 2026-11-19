import React, { useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import { Btn } from './ui.jsx'
import { formatTime } from './format.js'

/** Arrival is a location signal; only the payer's actual boarding opens this window. */
export default function BoardingCodePanel({ party, now, onUpdated, active = true, allowCall = false }) {
  const [state, setState] = useState(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const panel = useRef(null)
  const [localNow, setLocalNow] = useState(Date.now())
  useEffect(() => {
    let live = true
    let loading = false
    setState(null); setCode('')
    const load = async () => {
      if (loading) return
      loading = true
      try { const value = await api.boardingState(party.id); if (live) setState(value) }
      catch (e) { if (live) setError(e.message) }
      finally { loading = false }
    }
    if (active) load()
    const poll = setInterval(() => {
      if (active && document.visibilityState !== 'hidden' && !panel.current?.closest('[hidden]')) load()
    }, 5000)
    return () => { live = false; clearInterval(poll) }
  }, [party.id, party.payerId, party.status, party.boardedAt, party.calledAt, party.groupVersion, active])
  useEffect(() => {
    if (now) return undefined
    const timer = setInterval(() => setLocalNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [now])
  const act = async (work) => {
    setBusy(true); setError('')
    try { await work(); setCode(''); await onUpdated?.() } catch (e) { setError(e.message) }
    finally {
      try { setState(await api.boardingState(party.id)) } catch (e) { setError(e.message) }
      setBusy(false)
    }
  }
  if (!state) return error ? <p role="alert">{error}</p> : null
  const expired = state.deadlineAt && Date.parse(now || new Date(localNow).toISOString()) >= Date.parse(state.deadlineAt)
  const windowOpen = state.status === 'OPEN' && !expired
  const canCall = (allowCall || party.status === 'RIDE_UNCONFIRMED') && party.payer && !party.calledAt && party.canCall
  const showCode = party.payer && windowOpen && state.code
  const typing = !party.payer && !state.confirmed && windowOpen
  const ended = (state.status === 'EXPIRED' || expired) && !state.confirmed
  const actionable = canCall || state.canOpen || showCode || typing || state.canStart || ended || (!party.payer && state.confirmed)
  // 아직 할 일이 없으면 한 줄로만 알려 준다. 타기 전부터 큰 상자를 띄우지 않는다.
  if (!actionable) return <section ref={panel} className="board-hint" aria-label="탑승 코드 확인">
    <p>택시에 타면 결제자 화면의 6자리 번호로 탑승을 확인해요.</p>
    {error && <p role="alert">{error}</p>}
  </section>
  return <section ref={panel} className="next-action boarding" aria-label="탑승 코드 확인">
    <h2>{showCode ? '탑승 번호' : typing ? '탑승 번호를 입력해 주세요' : '택시에 타면 탑승을 확인해 주세요'}</h2>
    {showCode && <><strong className="ticket" aria-label="탑승 코드">{state.code}</strong><p>탑승한 일행에게 이 화면을 보여 주세요.</p></>}
    {state.deadlineAt && windowOpen && <p>입력 마감 {formatTime(state.deadlineAt)}</p>}
    {!showCode && !typing && !ended && <p>결제자가 탄 뒤 5분 동안 번호를 입력해요.</p>}
    {canCall && <Btn disabled={busy} onClick={() => act(() => api.callParty(party.id))}>택시 불렀어요</Btn>}
    {state.canOpen && <Btn disabled={busy} onClick={() => act(() => api.boarded(party.id))}>택시 탔어요 · 코드 열기</Btn>}
    {!party.payer && state.confirmed && <p className="ok">탑승 확인 완료</p>}
    {typing && <form className="code-form" onSubmit={(e) => { e.preventDefault(); act(() => api.confirmBoardingCode(party.id, code, state.generation)) }}>
      <label className="field code"><span className="sr-only">결제자 화면의 6자리 코드</span><input aria-label="6자리 탑승 코드" inputMode="numeric" autoComplete="off" maxLength={6} placeholder="000000" value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, ''))} /></label>
      <p>남은 입력 횟수 {state.remainingAttempts}회</p>
      <Btn disabled={busy || code.length !== 6 || state.remainingAttempts === 0} type="submit">탑승 확인</Btn>
    </form>}
    {ended && <p>번호 입력 시간이 끝났어요. 입력하지 못했다는 이유만으로 노쇼가 되지 않아요.</p>}
    {state.canStart && <><p>확인 안 된 일행이 있어도 출발할 수 있어요.</p><Btn disabled={busy} onClick={() => act(() => api.startRide(party.id))}>운행 시작</Btn></>}
    {error && <p role="alert">{error}</p>}
  </section>
}
